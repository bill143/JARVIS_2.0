"""Memory governance: confidence, provenance, TTL/decay, pin, conflict, user controls."""

from jarvis_memory.governance import MemoryGovernanceStore


def _store(settings):
    return MemoryGovernanceStore(settings.sqlite_path, settings)


def test_add_with_confidence_and_provenance(settings):
    store = _store(settings)
    item = store.add(tenant="t", user_id="u", text="the wifi password is in the safe",
                     confidence=0.8, provenance={"why": "user_told"})
    assert item["confidence"] == 0.8
    got = store.get(item["id"])
    assert got["provenance"]["why"] == "user_told"
    store.close()


def test_view_edit_delete(settings):
    store = _store(settings)
    item = store.add(tenant="t", user_id="u", text="original text")
    items = store.list("t", "u")
    assert any(i["id"] == item["id"] for i in items)
    store.edit(item["id"], text="corrected text", confidence=0.9)
    assert store.get(item["id"])["text"] == "corrected text"
    assert store.forget(item["id"]) is True
    assert store.get(item["id"])["status"] == "forgotten"
    store.close()


def test_pin_and_limit(settings):
    s = settings.model_copy(update={"memory_pin_limit_per_user": 1})
    store = MemoryGovernanceStore(s.sqlite_path, s)
    a = store.add(tenant="t", user_id="u", text="memory a")
    b = store.add(tenant="t", user_id="u", text="memory b")
    assert store.pin("t", "u", a["id"], True)["pinned"] is True
    over = store.pin("t", "u", b["id"], True)
    assert "error" in over  # pin limit reached
    store.close()


def test_conflict_detection_and_resolution(settings):
    store = _store(settings)
    a = store.add(tenant="t", user_id="u", text="the office is open on fridays")
    b = store.add(tenant="t", user_id="u", text="the office is not open on fridays")
    assert b["conflict_with"] == a["id"]
    conflicts = store.conflicts("t", "u")
    assert conflicts and conflicts[0]["status"] == "open"
    assert store.resolve_conflict(conflicts[0]["id"], keep_item=b["id"]) is True
    assert store.get(a["id"])["status"] == "superseded"
    store.close()


def test_export_personal_data(settings):
    store = _store(settings)
    store.add(tenant="t", user_id="u", text="exportable memory")
    export = store.export("t", "u")
    assert export["count"] >= 1
    assert export["items"]
    store.close()


def test_tenant_isolation(settings):
    store = _store(settings)
    store.add(tenant="tenantA", user_id="shared", text="alpha secret data")
    other = store.list("tenantB", "shared")
    assert all("alpha secret" not in i["text"] for i in other)
    store.close()


def test_decay_and_ttl(settings):
    store = _store(settings)
    item = store.add(tenant="t", user_id="u", text="decays over time", ttl_days=90)
    listed = store.list("t", "u")
    entry = next(i for i in listed if i["id"] == item["id"])
    assert "decayed_confidence" in entry
    assert entry["expires_at"]
    store.close()
