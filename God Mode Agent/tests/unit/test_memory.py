"""Vector memory write/read, persistence across restart, namespacing, context buffer."""

from jarvis_memory.metadata import MetadataStore
from jarvis_memory.short_term import ContextBuffer
from jarvis_memory.vector_store import LocalVectorStore, make_namespace
from jarvis_shared.schemas import Message


def test_upsert_and_search_relevance(tmp_path):
    store = LocalVectorStore(tmp_path)
    store.upsert("u1", "r1", "bananas are yellow tropical fruit")
    store.upsert("u1", "r2", "the stock market closed higher today")
    store.upsert("u1", "r3", "python is a programming language")
    hits = store.search("u1", "yellow banana fruit", k=2)
    assert hits[0]["id"] == "r1"
    assert hits[0]["score"] > 0


def test_persistence_across_restart(tmp_path):
    store = LocalVectorStore(tmp_path)
    store.upsert("u1", "r1", "remember the wifi password is stored in the safe")
    # Simulate process restart: brand-new instance over the same directory.
    reopened = LocalVectorStore(tmp_path)
    hits = reopened.search("u1", "wifi password", k=1)
    assert hits and hits[0]["id"] == "r1"


def test_namespace_isolation(tmp_path):
    store = LocalVectorStore(tmp_path)
    store.upsert("alice", "a1", "alice private note about project apollo")
    store.upsert("bob", "b1", "bob note about groceries")
    assert all(h["id"] != "a1" for h in store.search("bob", "project apollo", k=5))


def test_upsert_overwrites_same_id(tmp_path):
    store = LocalVectorStore(tmp_path)
    store.upsert("u1", "r1", "old text")
    store.upsert("u1", "r1", "new text about kubernetes clusters")
    hits = store.search("u1", "kubernetes", k=5)
    assert len(hits) == 1
    assert "kubernetes" in hits[0]["text"]


def test_make_namespace_sanitizes():
    assert make_namespace("user@example.com", "sess/1") == "user_example_com--sess_1"


def test_context_buffer_compresses_and_keeps_summary():
    buf = ContextBuffer(max_messages=10)
    for i in range(30):
        buf.add("s1", Message(role="user", content=f"message number {i}"))
    messages = buf.get("s1")
    assert len(messages) <= 11  # rolling window + 1 summary message
    assert messages[0].role == "system"
    assert "summary" in messages[0].content.lower()


def test_metadata_store_sessions_and_events(tmp_path):
    store = MetadataStore(tmp_path / "meta.db")
    store.touch_session("s1", "u1")
    store.log_session_event("s1", "chat", {"message": "hi"})
    logs = store.get_session_logs("s1")
    assert logs[0]["kind"] == "chat"
    assert logs[0]["payload"]["message"] == "hi"
    store.close()
