"""Advanced RAG: chunking, dedup/versioning, hybrid retrieval, reranking, citations."""

from jarvis_rag import HybridRetriever, IngestionPipeline, RagStore
from jarvis_rag.chunking import chunk_fixed, chunk_semantic


def test_chunking_strategies():
    text = "para one is here.\n\npara two is longer " + ("x " * 200)
    fixed = chunk_fixed(text, size=100, overlap=10)
    semantic = chunk_semantic(text, max_chars=120)
    assert len(fixed) >= 2
    assert len(semantic) >= 2


def _ingest(settings):
    store = RagStore(settings.sqlite_path)
    pipe = IngestionPipeline(store, settings)
    pipe.ingest_text(tenant="t", source="doc1", title="Quantum",
                     content="Quantum computing uses qubits and superposition to perform parallel computation.")
    pipe.ingest_text(tenant="t", source="doc2", title="Gardening",
                     content="Gardening involves planting seeds, watering soil, and harvesting vegetables.")
    return store, pipe


def test_dedup_and_versioning(settings):
    store, pipe = _ingest(settings)
    # identical content -> deduplicated
    dup = pipe.ingest_text(tenant="t", source="doc1b", title="Quantum",
                           content="Quantum computing uses qubits and superposition to perform parallel computation.")
    assert dup["deduplicated"] is True
    # same source, changed content -> version bump
    v2 = pipe.ingest_text(tenant="t", source="doc1", title="Quantum",
                          content="Quantum computing v2: qubits, superposition, and entanglement.")
    assert v2["version"] == 2
    store.close()


def test_hybrid_retrieval_and_citations(settings):
    store, _ = _ingest(settings)
    retriever = HybridRetriever(store, settings)
    result = retriever.retrieve("t", "qubits superposition quantum")
    assert result["hybrid"] is True
    assert result["results"]
    # top result should be the quantum doc, not gardening
    assert "quantum" in result["results"][0]["text"].lower() or "qubit" in result["results"][0]["text"].lower()
    assert result["citations"]
    assert all(c["marker"].startswith("[") for c in result["citations"])
    assert result["confidence"] > 0
    store.close()


def test_answer_has_citations_and_confidence(settings):
    store, _ = _ingest(settings)
    retriever = HybridRetriever(store, settings)
    ans = retriever.answer("t", "how does quantum computing work with qubits")
    assert ans["citations"]
    assert ans["segments"]
    assert all("citation" in s for s in ans["segments"])
    store.close()


def test_abstains_without_sources(settings):
    store = RagStore(settings.sqlite_path)
    retriever = HybridRetriever(store, settings)
    ans = retriever.answer("empty-tenant", "anything")
    assert ans["confidence"] == 0.0
    assert not ans["citations"]
    store.close()


def test_bm25_disabled_still_works(settings):
    s2 = settings.model_copy(update={"rag_bm25_enabled": False})
    store, _ = _ingest(s2)
    retriever = HybridRetriever(store, s2)
    result = retriever.retrieve("t", "quantum qubits")
    assert result["results"]
    store.close()
