package com.jarvis.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * An in-memory nearest-neighbour store: id → embedding vector, searched by cosine similarity.
 * Dependency-free (plain JDK arrays), which keeps a personal-scale index honest and offline. It
 * holds only vectors; the owning {@link SemanticMemory} maps hit ids back to documents.
 */
public final class VectorIndex {

    private final ConcurrentMap<String, float[]> vectors = new ConcurrentHashMap<>();

    /** A search hit: a document id and its cosine similarity to the query (higher is closer). */
    public record Scored(String id, double score) {
    }

    /** Stores {@code vector} under {@code id}, replacing any previous vector for that id. */
    public void put(String id, float[] vector) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(vector, "vector");
        vectors.put(id, vector.clone());
    }

    /** Removes the vector for {@code id}; returns whether one existed. */
    public boolean remove(String id) {
        return vectors.remove(id) != null;
    }

    /** Number of indexed vectors. */
    public int size() {
        return vectors.size();
    }

    /**
     * Returns the {@code k} ids whose vectors are most similar to {@code query} by cosine
     * similarity, ordered by descending score. Zero-similarity hits are dropped.
     */
    public List<Scored> search(float[] query, int k) {
        Objects.requireNonNull(query, "query");
        List<Scored> scored = new ArrayList<>();
        for (var e : vectors.entrySet()) {
            double s = cosine(query, e.getValue());
            if (s > 0) {
                scored.add(new Scored(e.getKey(), s));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        int limit = Math.max(0, k);
        return scored.size() > limit ? new ArrayList<>(scored.subList(0, limit)) : scored;
    }

    /** Cosine similarity of two vectors; 0 when either is empty, zero-length, or mismatched. */
    static double cosine(float[] a, float[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
