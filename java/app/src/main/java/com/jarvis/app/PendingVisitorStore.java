package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.memory.MemoryEntry;
import com.jarvis.memory.MemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Transient record of an unrecognized visitor awaiting a name, keyed by a random token. Backed by
 * a {@link MemoryStore} (no durability guarantee implied beyond whatever the injected store
 * provides) — entries carry their own {@code expiresAt} so a stale pending snapshot is treated as
 * gone even if nothing has actively cleaned it up yet.
 */
final class PendingVisitorStore {

    /** One unrecognized visitor awaiting a name. */
    record PendingVisitor(String token, String cameraId, String snapshotPath, String createdAt,
            String expiresAt) {
    }

    private static final String SCOPE = "vision-pending";

    private final MemoryStore<String> store;
    private final ObjectMapper mapper = new ObjectMapper();

    PendingVisitorStore(MemoryStore<String> store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Creates and stores a new pending visitor entry, returning it. */
    PendingVisitor create(String cameraId, String snapshotPath, Instant now, int ttlSec) {
        String token = UUID.randomUUID().toString();
        String createdAt = now.toString();
        String expiresAt = now.plusSeconds(ttlSec).toString();
        PendingVisitor visitor = new PendingVisitor(token, cameraId, snapshotPath, createdAt, expiresAt);
        store.put(SCOPE, token, toJson(visitor));
        return visitor;
    }

    /**
     * The pending visitor for {@code token}, or empty if absent or expired as of {@code now}.
     * Expired entries are opportunistically deleted from the backing store when found.
     */
    Optional<PendingVisitor> get(String token, Instant now) {
        if (token == null) {
            return Optional.empty();
        }
        Optional<PendingVisitor> found = store.get(SCOPE, token).map(entry -> fromJson(entry.value()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PendingVisitor visitor = found.get();
        Instant expiresAt;
        try {
            expiresAt = Instant.parse(visitor.expiresAt());
        } catch (Exception e) {
            // Unparseable expiry defaults to "already expired" rather than living forever.
            store.delete(SCOPE, token);
            return Optional.empty();
        }
        if (now.isAfter(expiresAt)) {
            store.delete(SCOPE, token);
            return Optional.empty();
        }
        return Optional.of(visitor);
    }

    /** Deletes the entry for {@code token}, if any. */
    void consume(String token) {
        if (token != null) {
            store.delete(SCOPE, token);
        }
    }

    /**
     * Full retention sweep: unlike {@link #get}, which only opportunistically deletes the single
     * entry being looked up, this walks every pending entry in the scope and deletes (from the
     * backing store AND from disk) every one whose {@code expiresAt} is at or before {@code now}.
     * A snapshot file that is already missing/unreadable is not treated as an error — the sweep
     * simply moves on to the next entry.
     *
     * @return how many entries were pruned
     */
    int pruneExpired(Instant now) {
        int pruned = 0;
        for (MemoryEntry<String> entry : store.query(SCOPE)) {
            PendingVisitor visitor;
            try {
                visitor = fromJson(entry.value());
            } catch (Exception e) {
                continue; // unreadable entry: nothing sensible to prune, leave it alone
            }
            Instant expiresAt;
            try {
                expiresAt = Instant.parse(visitor.expiresAt());
            } catch (Exception e) {
                expiresAt = Instant.EPOCH; // unparseable expiry defaults to "already expired"
            }
            if (!now.isBefore(expiresAt)) { // now >= expiresAt
                store.delete(SCOPE, visitor.token());
                try {
                    Files.deleteIfExists(Path.of(visitor.snapshotPath()));
                } catch (IOException e) {
                    // Best-effort: a missing/already-deleted file is not worth failing the sweep over.
                }
                pruned++;
            }
        }
        return pruned;
    }

    private String toJson(PendingVisitor visitor) {
        try {
            return mapper.writeValueAsString(visitor);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize pending visitor", e);
        }
    }

    private PendingVisitor fromJson(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return new PendingVisitor(n.path("token").asText(""), n.path("cameraId").asText(""),
                    n.path("snapshotPath").asText(""), n.path("createdAt").asText(""),
                    n.path("expiresAt").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize pending visitor", e);
        }
    }
}
