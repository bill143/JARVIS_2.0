"""Suite 4 — GovTribe adapter (T04-01 .. T04-07).

The adapter holds two responsibilities: read-only fetches from the GovTribe
MCP bridge, and idempotent promotion of cached opportunities into OCERP
projects via the OCERP service layer.  Both are exercised here with a fake
MCP client and a fake project-creation hook (the OCERP project table isn't
brought up in this test rig).
"""

import uuid
from unittest.mock import patch

import pytest

from app.modules.precon.repository import OpportunityCacheRepository
from app.modules.precon.services import GovTribeConnectionError
from app.modules.precon.services.govtribe_adapter import GovTribeAdapter

pytestmark = pytest.mark.asyncio


# ── Fakes ────────────────────────────────────────────────────────────────


class _FakeMCPClient:
    """Stand-in for the GovTribe MCP client used by the adapter."""

    def __init__(self, opportunities=None, raise_exc=None):
        self.opportunities = opportunities or []
        self.raise_exc = raise_exc

    async def search_opportunities(self, *, limit: int = 25):
        if self.raise_exc is not None:
            raise self.raise_exc
        return list(self.opportunities[:limit])


# ── T04-01: fetch_opportunities returns a list, no DB write ───────────────


async def test_fetch_returns_list_no_db_write(session):
    opps = [
        {"govtribe_id": f"GT-{i:032x}"[:32], "name": f"Opportunity {i}"}
        for i in range(5)
    ]
    adapter = GovTribeAdapter(session, mcp_client=_FakeMCPClient(opps))

    fetched = await adapter.fetch_opportunities(limit=5)

    assert len(fetched) == 5
    cache = OpportunityCacheRepository(session)
    # No entries in the cache because we only fetched, never cached.
    assert await cache.latest_sync() is None


# ── T04-02 + T04-07: promote creates project + cache row ──────────────────


async def test_promote_creates_project_and_cache_row(session):
    opp = {
        "govtribe_id": "a" * 32,
        "solicitation_number": "36C25225B0026",
        "name": "Federal Roof Replacement",
        "due_date": "2026-09-15",
        "federal_agency": {"name": "Department of Veterans Affairs"},
    }
    project_id = uuid.uuid4()
    adapter = GovTribeAdapter(session, mcp_client=_FakeMCPClient([opp]))
    await adapter.cache_opportunity(opp)

    async def _fake_create(self, payload, *, owner_id):  # noqa: ARG001
        return project_id

    with patch.object(GovTribeAdapter, "_create_ocerp_project", _fake_create):
        result = await adapter.promote_opportunity("a" * 32)

    assert result.project_id == project_id
    assert not result.already_promoted

    cache = OpportunityCacheRepository(session)
    cached = await cache.get_by_external_id("a" * 32)
    assert cached is not None
    assert cached.solicitation_number == "36C25225B0026"
    assert cached.promoted_to_project_id == project_id


# ── T04-03: re-promotion is idempotent ─────────────────────────────────────


async def test_re_promotion_is_idempotent(session):
    gt_id = "b" * 32
    opp = {
        "govtribe_id": gt_id,
        "solicitation_number": "36C25226B0001",
        "name": "Idempotency Test",
    }
    project_id = uuid.uuid4()
    adapter = GovTribeAdapter(session, mcp_client=_FakeMCPClient([opp]))
    await adapter.cache_opportunity(opp)

    call_count = 0

    async def _fake_create(self, payload, *, owner_id):  # noqa: ARG001
        nonlocal call_count
        call_count += 1
        return project_id

    with patch.object(GovTribeAdapter, "_create_ocerp_project", _fake_create):
        first = await adapter.promote_opportunity(gt_id)
        second = await adapter.promote_opportunity(gt_id)

    assert first.project_id == second.project_id == project_id
    assert second.already_promoted is True
    assert call_count == 1  # OCERP project only created once


# ── T04-04: govtribe_adapter contains zero raw cross-module SQL ───────────


def test_adapter_uses_no_cross_module_raw_sql():
    import inspect

    from app.modules.precon.services import govtribe_adapter as mod

    source = inspect.getsource(mod)
    # The adapter may touch its OWN cache table, but not any other oe_* table.
    forbidden = ("oe_tendering_", "oe_finance_", "oe_documents_", "oe_procurement_", "oe_rfq_")
    for token in forbidden:
        assert token not in source, f"govtribe_adapter must not reference {token!r}"


# ── T04-05: disconnected MCP raises GovTribeConnectionError ───────────────


async def test_disconnected_mcp_raises(session):
    adapter = GovTribeAdapter(
        session,
        mcp_client=_FakeMCPClient(raise_exc=GovTribeConnectionError("upstream down")),
    )
    with pytest.raises(GovTribeConnectionError):
        await adapter.fetch_opportunities()


# ── T04-06: protected zone — OCERP source code contains zero govtribe references ─


def test_ocerp_source_has_zero_govtribe_references():
    """Static scan: OCERP application source (outside the precon module)
    must contain zero ``govtribe`` matches.

    Rule of Engagement #3 puts the GovTribe / MCP bridge in the NEXUS Precon
    module — and only there.  After Phase 7.1 integration the precon module
    lives at ``app/modules/precon/`` *inside* OCERP, so the scan excludes
    that subtree (those are precon files we own, not OCERP files we'd be
    polluting).  Anywhere else in OCERP source must remain govtribe-free.
    """
    import os
    from pathlib import Path

    here = Path(__file__).resolve()
    precon_root = here.parent.parent  # ``backend/app/modules/precon``
    src_root = precon_root.parent.parent  # ``backend/app``

    if not src_root.exists() or src_root.name != "app":
        pytest.skip(f"OCERP source not present at {src_root}; skipping protected-zone scan")

    matches: list[str] = []
    for root, dirs, files in os.walk(src_root):
        if "__pycache__" in root:
            continue
        # Prune the precon module so we don't recurse through our own files.
        try:
            if Path(root).resolve().is_relative_to(precon_root):
                dirs[:] = []
                continue
        except (AttributeError, ValueError):
            if str(precon_root) in str(Path(root).resolve()):
                dirs[:] = []
                continue
        for fn in files:
            if not fn.endswith(".py"):
                continue
            path = Path(root) / fn
            try:
                text = path.read_text(encoding="utf-8", errors="ignore").lower()
            except Exception:
                continue
            if "govtribe" in text:
                matches.append(str(path))
    assert matches == [], (
        "OCERP source (outside the precon module) contains govtribe references "
        "— protected zone violated:\n" + "\n".join(matches[:10])
    )


# ── Amendment A2 — udiff parser (extract_clean_description) ───────────────


def test_extract_clean_description_empty_input():
    """Empty list, None, non-list, missing udiff key — all return ''."""
    from app.modules.precon.services.govtribe_adapter import extract_clean_description

    assert extract_clean_description([]) == ""
    assert extract_clean_description(None) == ""
    assert extract_clean_description("not a list") == ""
    assert extract_clean_description([{}]) == ""
    assert extract_clean_description([{"udiff": ""}]) == ""
    assert extract_clean_description([{"udiff": None}]) == ""
    assert extract_clean_description(["string entry, not dict"]) == ""


def test_extract_clean_description_single_hunk():
    """Single hunk with header, file markers, additions, and deletions."""
    from app.modules.precon.services.govtribe_adapter import extract_clean_description

    udiff = (
        "--- a/desc\n"
        "+++ b/desc\n"
        "@@ -1,3 +1,4 @@\n"
        " Existing scope line\n"
        "+New requirement added\n"
        "-Old requirement removed\n"
        " Trailing context"
    )
    out = extract_clean_description([{"udiff": udiff}])
    assert out == "Existing scope line\nNew requirement added\n Trailing context"
    # File markers, hunk header, deletion line all dropped.
    assert "@@" not in out
    assert "---" not in out
    assert "+++" not in out
    assert "Old requirement removed" not in out


def test_extract_clean_description_multiple_hunks():
    """Multiple @@ hunks — every header skipped, every body line kept appropriately."""
    from app.modules.precon.services.govtribe_adapter import extract_clean_description

    udiff = (
        "@@ -1,2 +1,2 @@\n"
        " section A line 1\n"
        "+section A added\n"
        "@@ -10,2 +11,2 @@\n"
        " section B line 1\n"
        "-section B removed\n"
        "+section B added"
    )
    out = extract_clean_description([{"udiff": udiff}])
    assert "section A line 1" in out
    assert "section A added" in out
    assert "section B line 1" in out
    assert "section B added" in out
    assert "section B removed" not in out
    assert "@@" not in out


def test_extract_clean_description_plain_text_fallback():
    """A udiff with no diff syntax (plain text body) is returned unchanged."""
    from app.modules.precon.services.govtribe_adapter import extract_clean_description

    plain = "This is a plain description with no diff syntax.\nSecond line."
    out = extract_clean_description([{"udiff": plain}])
    assert out == plain


def test_extract_clean_description_uses_first_entry_only():
    """When multiple entries exist, only descriptions[0] is parsed (most recent)."""
    from app.modules.precon.services.govtribe_adapter import extract_clean_description

    out = extract_clean_description(
        [
            {"udiff": "@@ -1 +1 @@\n+latest text"},
            {"udiff": "@@ -1 +1 @@\n+older text — should be ignored"},
        ]
    )
    assert "latest text" in out
    assert "older text" not in out


# ── Amendment A2 — nested-field extractors ────────────────────────────────


def test_extract_agency_name_returns_nested_name():
    """A well-formed federal_agency object yields the agency name string."""
    from app.modules.precon.services.govtribe_adapter import extract_agency_name

    payload = {
        "federal_agency": {
            "name": "Department of Veterans Affairs",
            "govtribe_id": "agency-uuid",
        }
    }
    assert extract_agency_name(payload) == "Department of Veterans Affairs"


def test_extract_agency_name_handles_missing_or_malformed():
    """Missing key, None value, non-dict value, or missing inner name returns None."""
    from app.modules.precon.services.govtribe_adapter import extract_agency_name

    assert extract_agency_name({}) is None
    assert extract_agency_name({"federal_agency": None}) is None
    assert extract_agency_name({"federal_agency": "string-not-dict"}) is None
    assert extract_agency_name({"federal_agency": {}}) is None
    assert extract_agency_name({"federal_agency": {"name": None}}) is None
    assert extract_agency_name({"federal_agency": {"name": 123}}) is None


def test_extract_place_of_performance_handles_null_payload():
    """place_of_performance is often null in real responses — must not crash."""
    from app.modules.precon.services.govtribe_adapter import extract_place_of_performance

    assert extract_place_of_performance({"place_of_performance": {"name": "Atlanta, GA"}}) == "Atlanta, GA"
    assert extract_place_of_performance({"place_of_performance": None}) is None
    assert extract_place_of_performance({}) is None


def test_extract_naics_code_from_nested_govtribe_id():
    """NAICS code lives at naics_category.govtribe_id, not at the top level."""
    from app.modules.precon.services.govtribe_adapter import extract_naics_code

    payload = {
        "naics_category": {
            "govtribe_id": "236220",
            "name": "Commercial and Institutional Building Construction",
        }
    }
    assert extract_naics_code(payload) == "236220"
    assert extract_naics_code({"naics_category": {}}) is None
    assert extract_naics_code({}) is None


# ── Amendment A2 — canonical end-to-end regression test ──────────────────


async def test_amendment_a2_canonical_schema(session):
    """Canonical Amendment A2 schema test — locks in the verified live GovTribe field names.

    DO NOT modify this test to use fallback field names like 'id', 'title', or
    'response_deadline'. Those exist as backward-compat fallbacks in the adapter
    but the canonical contract is verified-live (govtribe_id, name, due_date,
    nested federal_agency, etc.). If this test fails after a code change, the
    adapter has silently regressed — fix the adapter, not the test.
    """
    # Realistic VA opportunity payload — every key is the verified-live name.
    govtribe_id = "f3a9d2b1c4e7f8a0d2b1c4e7f8a0d2b1"  # 32-char hex
    udiff_body = (
        "--- a/desc\n"
        "+++ b/desc\n"
        "@@ -1,4 +1,5 @@\n"
        " VA Medical Center — Roof Replacement\n"
        " Scope includes membrane removal, deck inspection, and full TPO replacement.\n"
        "+Davis-Bacon wage determination applies.\n"
        "-Project not yet awarded\n"
        "@@ -20,2 +21,2 @@\n"
        " Pre-bid site walk required prior to submission.\n"
        "+Bid bond: 5% of proposal value."
    )
    canonical_payload: dict = {
        "govtribe_id": govtribe_id,
        "govtribe_type": "opportunity",
        "govtribe_url": "https://govtribe.com/opportunities/" + govtribe_id,
        "solicitation_number": "36C25225B0026",
        "name": "VA Medical Center — Roof Replacement",
        "opportunity_type": "Solicitation",
        "opportunity_state": None,
        "set_aside_type": "SDVOSB",
        "posted_date": "2026-04-01T00:00:00Z",
        "due_date": "2026-09-15T17:00:00Z",
        "award_date": None,
        "descriptions": [{"udiff": udiff_body}],
        "federal_meta_opportunity_id": "VA-meta-12345",
        "updated_at": "2026-05-02T12:34:56Z",
        "federal_agency": {
            "name": "Department of Veterans Affairs",
            "govtribe_id": "agency-va-uuid",
        },
        "place_of_performance": {"name": "Atlanta, GA"},
        "naics_category": {
            "govtribe_id": "236220",
            "name": "Commercial and Institutional Building Construction",
        },
        "psc_category": {"govtribe_id": "Y1FA", "name": "Construction of Hospitals"},
        "points_of_contact": [],
    }

    adapter = GovTribeAdapter(session, mcp_client=_FakeMCPClient([canonical_payload]))

    # Step 1: cache the opportunity using the VERIFIED-LIVE field names only.
    cache_id = await adapter.cache_opportunity(canonical_payload)
    assert cache_id is not None

    # Step 2: promote it.  We force the ORM fallback path by making the
    # primary path's ProjectService unavailable.  This keeps the test
    # hermetic — the OCERP service path drags in audit-log + event-bus +
    # default-team writes that need their own tables which we don't bring
    # up here.  Both paths produce the same shape: agency / NAICS bundle
    # in either ``custom_fields`` (primary) or ``metadata_`` (ORM fallback)
    # — the assertions below accept either location.
    import sys

    real_service_module = sys.modules.pop("app.modules.projects.service", None)
    sys.modules["app.modules.projects.service"] = None  # type: ignore[assignment]
    try:
        result = await adapter.promote_opportunity(govtribe_id)
    finally:
        if real_service_module is not None:
            sys.modules["app.modules.projects.service"] = real_service_module
        else:
            sys.modules.pop("app.modules.projects.service", None)

    # Step 3: project_id surfaced.
    assert result.project_id is not None
    assert result.cache_id == cache_id
    assert result.already_promoted is False

    # Step 4: cache row exists with the correct govtribe_external_id.
    cache = OpportunityCacheRepository(session)
    cached = await cache.get_by_external_id(govtribe_id)
    assert cached is not None
    assert cached.govtribe_external_id == govtribe_id
    assert cached.solicitation_number == "36C25225B0026"
    assert cached.promoted_to_project_id == result.project_id

    # Step 5: cleaned description (parsed from udiff) landed on the project.
    # Step 6: agency / NAICS / place_of_performance landed on the project,
    # whether through ProjectCreate.custom_fields (primary path) or
    # Project.metadata_ (ORM fallback path).
    from app.modules.projects.models import Project  # type: ignore[import-not-found]

    project = await session.get(Project, result.project_id)
    assert project is not None, "Promoted Project not found in test DB"

    # Description: parsed udiff, deletions dropped, additions kept w/o + prefix.
    assert "VA Medical Center — Roof Replacement" in (project.description or "")
    assert "Davis-Bacon wage determination applies." in (project.description or "")
    assert "Bid bond: 5% of proposal value." in (project.description or "")
    assert "Project not yet awarded" not in (project.description or ""), (
        "Deletion line leaked into the description — udiff parser regressed"
    )
    assert "@@" not in (project.description or "")

    # Phase set to 'identified' for a fresh opportunity.
    assert project.phase == "identified"

    # Bid due date stored as a plain date (validator-friendly format).
    assert project.planned_end_date == "2026-09-15"

    # Place of performance landed on the address JSON.
    assert project.address == {"name": "Atlanta, GA"}

    # GovTribe metadata bundle landed on either custom_fields (primary path)
    # or metadata_ (ORM fallback).  Accept whichever path actually ran in
    # this test rig — both are contract-valid.
    bundle = project.custom_fields or project.metadata_.get("govtribe") or {}
    assert bundle.get("agency") == "Department of Veterans Affairs"
    assert bundle.get("naics_code") == "236220"
    assert bundle.get("solicitation_number") == "36C25225B0026"
    assert bundle.get("govtribe_id") == govtribe_id
    assert bundle.get("govtribe_url") == canonical_payload["govtribe_url"]
    assert bundle.get("source") == "govtribe"

    # Step 7: bid_due milestone created, named with the solicitation number.
    from sqlalchemy import select

    from app.modules.projects.models import ProjectMilestone  # type: ignore[import-not-found]

    milestones = (
        await session.execute(
            select(ProjectMilestone).where(ProjectMilestone.project_id == result.project_id)
        )
    ).scalars().all()
    assert len(milestones) == 1, f"Expected exactly one milestone, got {len(milestones)}"
    bid_due = milestones[0]
    assert bid_due.milestone_type == "bid_due"
    assert bid_due.name == "Bid Due — 36C25225B0026"
