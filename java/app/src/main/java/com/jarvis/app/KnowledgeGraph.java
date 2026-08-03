package com.jarvis.app;

import com.jarvis.rag.Document;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the knowledge galaxy: the unified store rendered as a graph of nodes (one per stored
 * document) linked by shared keywords. Node {@code id} is the document's index in {@code semantic
 * .all()} — the SAME index the chat handler returns in {@code sources[].id} — so a cited source
 * flies straight to its node.
 *
 * <p>Links are derived from keyword co-occurrence: two notes that share a distinctive term are
 * connected. Terms that appear in nearly every note (or in only one) carry no signal and are
 * skipped, which keeps the graph from collapsing into a hairball or scattering into dust. Everything
 * is bounded so a huge vault can't produce a quadratic explosion of edges.
 */
final class KnowledgeGraph {

    private static final int MAX_NODES = 600;
    private static final int MAX_LINKS = 1500;
    /** A term links notes only when it is shared by a small, meaningful cluster (not everywhere). */
    private static final int MIN_CLUSTER = 2;
    private static final int MAX_CLUSTER = 12;

    private KnowledgeGraph() {
    }

    /** One galaxy node. {@code val} scales its size; {@code source} groups/colours it. */
    record Node(int id, String docId, String title, String source, int val) {
    }

    /** An undirected edge between two node ids. */
    record Link(int source, int target) {
    }

    /** The assembled graph plus whether it was capped (so the UI can say so honestly). */
    record Graph(List<Node> nodes, List<Link> links, boolean truncated) {
    }

    static Graph build(List<Document> docs) {
        List<Node> nodes = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        if (docs == null || docs.isEmpty()) {
            return new Graph(nodes, links, false);
        }
        boolean truncated = docs.size() > MAX_NODES;
        int n = Math.min(docs.size(), MAX_NODES);

        // Nodes, and an inverted index term -> node ids that hold it (bounded posting lists).
        Map<String, List<Integer>> postings = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Document d = docs.get(i);
            String title = d.metadata() == null ? "" : d.metadata().getOrDefault("title", "");
            String source = SemanticMemoryService.sourceOf(d);
            if (source.isBlank()) {
                source = "memory";
            }
            int val = 1 + Math.min(6, d.content() == null ? 0 : d.content().length() / 400);
            nodes.add(new Node(i, d.id(), title, source, val));

            Set<String> seen = new HashSet<>();
            // Title terms carry the most signal for clustering; include a bounded slice of the body.
            seen.addAll(KnowledgeGrounding.tokens(title));
            List<String> bodyTerms = KnowledgeGrounding.tokens(d.content());
            for (int t = 0; t < Math.min(bodyTerms.size(), 40); t++) {
                seen.add(bodyTerms.get(t));
            }
            for (String term : seen) {
                postings.computeIfAbsent(term, k -> new ArrayList<>()).add(i);
            }
        }

        // Link members of each meaningful term cluster (star from the lowest id — deterministic).
        Set<Long> edges = new HashSet<>();
        outer:
        for (List<Integer> cluster : postings.values()) {
            int size = cluster.size();
            if (size < MIN_CLUSTER || size > MAX_CLUSTER) {
                continue;
            }
            int hub = cluster.get(0);
            for (int j = 1; j < size; j++) {
                int a = Math.min(hub, cluster.get(j));
                int b = Math.max(hub, cluster.get(j));
                if (a == b) {
                    continue;
                }
                long key = ((long) a << 20) | b;
                if (edges.add(key)) {
                    links.add(new Link(a, b));
                    if (links.size() >= MAX_LINKS) {
                        truncated = true;
                        break outer;
                    }
                }
            }
        }
        return new Graph(nodes, links, truncated);
    }
}
