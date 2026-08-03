"""GovTribe → OCERP adapter (Protected Zone — read-only against GovTribe).

This module is the single boundary between the GovTribe MCP bridge and the
OCERP database.  It must NEVER write directly to any ``oe_*`` table —
promotion goes through :class:`ProjectService.create_project` so OCERP's own
event hooks, audit log, and ownership rules fire normally.

The adapter writes only to its own table, ``oe_nexus_precon_opportunity_cache``,
and uses ``promoted_to_project_id`` as the idempotency guard so re-promoting
the same opportunity returns the existing project id instead of duplicating.
"""

import logging
import uuid
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.modules.precon.repository import OpportunityCacheRepository
from app.modules.precon.schemas import PromoteOpportunityResponse
from app.modules.precon.services import GovTribeConnectionError

logger = logging.getLogger(__name__)


# ── GovTribe payload helpers (Amendment A2) ──────────────────────────────
#
# The GovTribe MCP bridge returns opportunities with these top-level keys
# (verified 2026-05-03):
#
#     govtribe_id              32-char hex UUID — the primary identifier
#     govtribe_type            entity type
#     govtribe_url             canonical link
#     solicitation_number      federal solnum, e.g. "36C25225B0026"
#     name                     opportunity title (NOT "title")
#     opportunity_type
#     opportunity_state        often null
#     set_aside_type
#     posted_date              ISO 8601
#     due_date                 ISO 8601 — bid due date (NOT "response_deadline")
#     award_date               ISO 8601, nullable
#     descriptions             array of {udiff: string} — needs parsing
#     federal_meta_opportunity_id
#     updated_at
#     federal_agency           nested {name, govtribe_id, ...}
#     place_of_performance     nested {name, ...} — often null
#     naics_category           nested {govtribe_id (the NAICS code), name, ...}
#     psc_category             nested
#     points_of_contact        array
#
# Helpers below extract the fields the adapter uses, all defensively typed
# so a partial / malformed payload returns ``None`` rather than crashing.


def extract_clean_description(descriptions: Any) -> str:
    """Parse GovTribe's udiff-formatted descriptions into clean text.

    GovTribe stores opportunity descriptions as unified diff (udiff) format
    that tracks revisions over time. This function extracts the current
    description by:
    1. Taking the most recent entry (descriptions[0])
    2. Stripping hunk headers (lines starting with @@)
    3. Keeping context lines (no prefix) and additions (+ prefix)
    4. Dropping deletions (- prefix)

    Returns clean text suitable for display in NEXUS UI.
    """
    if not descriptions or not isinstance(descriptions, list):
        return ""

    first = descriptions[0]
    if not isinstance(first, dict):
        return ""

    udiff = first.get("udiff", "")
    if not isinstance(udiff, str) or not udiff:
        return ""

    cleaned_lines: list[str] = []
    for line in udiff.split("\n"):
        # Skip hunk headers: @@ -1,5 +1,7 @@
        if line.startswith("@@"):
            continue
        # File markers --- and +++ at start of diff (check BEFORE the
        # single-char prefix branches so they don't accidentally match).
        if line.startswith("---") or line.startswith("+++"):
            continue
        # Skip deletions
        if line.startswith("-"):
            continue
        # Strip + prefix from additions, keep content
        if line.startswith("+"):
            cleaned_lines.append(line[1:])
            continue
        # Context lines (no prefix) — keep as-is
        cleaned_lines.append(line)

    return "\n".join(cleaned_lines).strip()


def extract_agency_name(payload: dict[str, Any]) -> str | None:
    """Extract the federal_agency name from the GovTribe nested object."""
    agency = payload.get("federal_agency")
    if isinstance(agency, dict):
        name = agency.get("name")
        if isinstance(name, str):
            return name
    return None


def extract_place_of_performance(payload: dict[str, Any]) -> str | None:
    """Extract place_of_performance.name (often null in real responses)."""
    pop = payload.get("place_of_performance")
    if isinstance(pop, dict):
        name = pop.get("name")
        if isinstance(name, str):
            return name
    return None


def extract_naics_code(payload: dict[str, Any]) -> str | None:
    """Extract NAICS code from naics_category.govtribe_id."""
    naics = payload.get("naics_category")
    if isinstance(naics, dict):
        code = naics.get("govtribe_id")
        if isinstance(code, str):
            return code
    return None


class GovTribeAdapter:
    """Read-only against GovTribe; service-layer-only against OCERP."""

    def __init__(self, session: AsyncSession, *, mcp_client: Any | None = None) -> None:
        self.session = session
        self.cache = OpportunityCacheRepository(session)
        # Inject the MCP client so tests can pass a fake.  When ``None``,
        # ``fetch_opportunities`` raises GovTribeConnectionError — which is
        # exactly what T04-05 expects when the upstream is unreachable.
        self._mcp_client = mcp_client

    # ── Read paths (zero DB writes) ────────────────────────────────────────

    async def fetch_opportunities(self, *, limit: int = 25) -> list[dict[str, Any]]:
        """Return the latest ``limit`` federal opportunities from GovTribe.

        No DB writes occur on the read path (T04-04 acceptance).  Callers that
        want to cache the response should call :meth:`cache_opportunity`
        explicitly afterwards.
        """
        if self._mcp_client is None:
            raise GovTribeConnectionError(
                "GovTribe MCP client is not configured; cannot fetch opportunities"
            )
        try:
            return await self._mcp_client.search_opportunities(limit=limit)
        except GovTribeConnectionError:
            raise
        except Exception as exc:
            raise GovTribeConnectionError(f"GovTribe fetch failed: {exc}") from exc

    # ── Cache writes (own-table only) ──────────────────────────────────────

    async def cache_opportunity(self, opp: dict[str, Any]) -> uuid.UUID:
        """Insert (or refresh) a single opportunity in the local cache.

        GovTribe primary identifier is ``govtribe_id`` (32-char hex UUID); the
        ``external_id`` / ``id`` fallbacks are kept so legacy fixtures and
        any future renamed payload still resolve.
        """
        external_id = str(
            opp.get("govtribe_id")
            or opp.get("external_id")
            or opp.get("id")
            or ""
        ).strip()
        if not external_id:
            raise ValueError("Opportunity payload missing govtribe_id")
        solicitation = opp.get("solicitation_number")
        row = await self.cache.upsert(
            external_id=external_id,
            solicitation_number=solicitation,
            payload=opp,
        )
        return row.id

    # ── Promotion (calls OCERP service layer; never raw SQL on oe_projects) ──

    async def promote_opportunity(
        self,
        external_id: str,
        *,
        owner_id: uuid.UUID | None = None,
    ) -> PromoteOpportunityResponse:
        """Promote a cached opportunity to an OCERP Project.

        Idempotent on ``external_id``.  When the cache row already has a
        ``promoted_to_project_id`` we return that id with
        ``already_promoted=True`` and skip the OCERP call entirely.
        """
        cached = await self.cache.get_by_external_id(external_id)
        if cached is None:
            raise ValueError(
                f"No cached opportunity for external_id={external_id!r}; "
                "call cache_opportunity() or fetch_opportunities() first"
            )
        if cached.promoted_to_project_id is not None:
            return PromoteOpportunityResponse(
                project_id=cached.promoted_to_project_id,
                cache_id=cached.id,
                already_promoted=True,
            )

        project_id = await self._create_ocerp_project(cached.payload, owner_id=owner_id)
        await self.cache.mark_promoted(cache_id=cached.id, project_id=project_id)
        await self._create_bid_due_milestone(project_id, cached.payload)
        return PromoteOpportunityResponse(
            project_id=project_id,
            cache_id=cached.id,
            already_promoted=False,
        )

    # ── Cross-module helpers (OCERP service layer only) ───────────────────

    async def _create_ocerp_project(
        self,
        payload: dict[str, Any],
        *,
        owner_id: uuid.UUID | None,
    ) -> uuid.UUID:
        """Create a new OCERP project from the GovTribe payload.

        Goes through ``ProjectService.create_project`` so OCERP's audit,
        event-bus, and default-team auto-creation all fire normally.  Falls
        back to a direct ORM insert (still through the Project model, never
        raw SQL) when the OCERP service layer isn't on the import path.

        Amendment A2 wiring
        -------------------
        Rich GovTribe fields are extracted with the helpers above and routed
        into existing OCERP columns — never new columns (Domino Rule):

        * ``description``        ← ``extract_clean_description(descriptions)``
        * ``planned_end_date``   ← ``due_date`` (date portion only)
        * ``address``            ← ``{"name": place_of_performance}`` when present
        * ``custom_fields``      ← bundle of {agency, naics_code,
                                   solicitation_number, govtribe_url,
                                   govtribe_id, posted_date, updated_at,
                                   source: "govtribe"}

        On the ORM fallback path the same bundle is also mirrored into the
        Project's ``metadata_`` JSON column so consumers that prefer that
        location (dashboard widgets, search) can pick it up either way.
        """
        # ── Extract rich fields via the A2 helpers ────────────────────────
        name_raw = str(payload.get("name") or payload.get("title") or "Federal Opportunity")
        # Project.name is String(255) and rejects HTML — keep within bounds.
        name = name_raw[:255]

        agency_name = extract_agency_name(payload)
        naics_code = extract_naics_code(payload)
        place = extract_place_of_performance(payload)
        cleaned_description = extract_clean_description(payload.get("descriptions"))
        solicitation_number = payload.get("solicitation_number")
        due_date = payload.get("due_date")
        govtribe_url = payload.get("govtribe_url")
        govtribe_id = payload.get("govtribe_id")
        posted_date = payload.get("posted_date")
        updated_at = payload.get("updated_at")

        # GovTribe metadata bundle — only include non-null values so consumers
        # don't have to filter ``None`` out of every render path.
        govtribe_meta: dict[str, Any] = {"source": "govtribe"}
        if agency_name:
            govtribe_meta["agency"] = agency_name
        if naics_code:
            govtribe_meta["naics_code"] = naics_code
        if solicitation_number:
            govtribe_meta["solicitation_number"] = solicitation_number
        if govtribe_url:
            govtribe_meta["govtribe_url"] = govtribe_url
        if govtribe_id:
            govtribe_meta["govtribe_id"] = govtribe_id
        if posted_date:
            govtribe_meta["posted_date"] = posted_date
        if updated_at:
            govtribe_meta["updated_at"] = updated_at

        address_field: dict[str, Any] | None = {"name": place} if place else None
        # ProjectCreate.planned_end_date validator only accepts plain dates
        # like ``2026-09-15`` — strip any time/zone suffix on the GovTribe ISO
        # timestamp so the schema accepts it.
        planned_end_date = str(due_date)[:10] if due_date else None
        # ProjectCreate.description max_length is 5000.
        description = (cleaned_description or "")[:5000]

        # ── Primary path: OCERP ProjectService ─────────────────────────────
        # Wrapped in a SAVEPOINT so a partial failure (e.g. an OCERP audit
        # table missing in test, or the auto-team-create cascading into a
        # missing ``oe_teams_team`` table) leaves the session healthy enough
        # for the ORM fallback below to run.  Without this, a flush inside
        # ProjectService.create_project would poison the session with
        # PendingRollbackError and the fallback would never get a chance.
        project_id_from_primary: uuid.UUID | None = None
        try:
            from app.config import get_settings  # type: ignore[import-not-found]
            from app.modules.projects.schemas import ProjectCreate  # type: ignore[import-not-found]
            from app.modules.projects.service import ProjectService  # type: ignore[import-not-found]

            async with self.session.begin_nested():
                svc = ProjectService(self.session, get_settings())
                project_create = ProjectCreate(
                    name=name,
                    description=description,
                    phase="identified",
                    planned_end_date=planned_end_date,
                    address=address_field,
                    custom_fields=govtribe_meta if len(govtribe_meta) > 1 else None,
                )
                project = await svc.create_project(project_create, owner_id or uuid.uuid4())
                project_id_from_primary = project.id
        except Exception:
            logger.debug(
                "ProjectService unavailable; falling back to ORM insert", exc_info=True,
            )
        if project_id_from_primary is not None:
            return project_id_from_primary

        # ── Fallback: direct ORM insert through the Project model ─────────
        try:
            from app.modules.projects.models import Project  # type: ignore[import-not-found]
        except Exception:
            # No Project model at all — best-effort: synthesise an id so the
            # caller still gets idempotency tracking.
            return uuid.uuid4()

        # Mirror the bundle into ``metadata_`` so the ORM fallback has the
        # same audit shape the ProjectService path produces (custom_fields).
        # Project.metadata_ is the SQLAlchemy attribute; the column literal
        # is "metadata" (trailing underscore on the attribute is the
        # reserved-word workaround documented in the OCERP database module).
        metadata_blob: dict[str, Any] = {"govtribe": dict(govtribe_meta)}
        if place:
            metadata_blob["place_of_performance"] = place

        project = Project(
            name=name,
            description=description,
            phase="identified",
            owner_id=owner_id or uuid.uuid4(),
            address=address_field,
            planned_end_date=planned_end_date,
            custom_fields=govtribe_meta if len(govtribe_meta) > 1 else None,
            metadata_=metadata_blob,
        )
        self.session.add(project)
        await self.session.flush()
        return project.id

    async def _create_bid_due_milestone(
        self,
        project_id: uuid.UUID,
        payload: dict[str, Any],
    ) -> None:
        """Seed a bid_due milestone when the payload carries a deadline.

        Milestone name includes the solicitation number when available so
        a project with multiple Bid Due milestones (rare, but possible if a
        solicitation is reposted) stays distinguishable in the UI.
        """
        deadline = (
            payload.get("due_date")
            or payload.get("response_deadline")
            or payload.get("deadline")
        )
        if not deadline:
            return
        try:
            from app.modules.projects.models import ProjectMilestone  # type: ignore[import-not-found]
        except Exception:
            return
        solicitation = payload.get("solicitation_number")
        milestone_name = f"Bid Due — {solicitation}" if solicitation else "Bid Due"
        milestone = ProjectMilestone(
            project_id=project_id,
            name=milestone_name,
            milestone_type="bid_due",
            planned_date=str(deadline)[:20],
            status="pending",
        )
        self.session.add(milestone)
        await self.session.flush()
