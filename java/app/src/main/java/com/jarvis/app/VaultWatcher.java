package com.jarvis.app;

/**
 * Keeps the unified semantic store in step with the Obsidian vault automatically — so grounding no
 * longer depends on a manual "Connect" click. On {@link #start} it mirrors the vault once, then a
 * daemon thread polls a cheap {@link BrainVault#fingerprint() fingerprint} of the vault every few
 * seconds; when files change on disk it re-indexes the vault and re-mirrors it into the store.
 *
 * <p>Polling (rather than a {@code WatchService}) is deliberate: it is cross-platform, needs no
 * per-directory key management as notes are added/removed under nested folders, and a few seconds of
 * latency is imperceptible for a personal vault. All work happens off the request path.
 */
final class VaultWatcher implements AutoCloseable {

    private volatile boolean running = true;
    private volatile Thread thread;

    private VaultWatcher() {
    }

    /**
     * Mirrors the vault once now, then starts a background watcher (when a vault is configured). Safe
     * to call with null collaborators or an unconfigured vault — it simply does nothing further.
     */
    static VaultWatcher start(BrainVault brain, SemanticMemoryService semantic, long pollMillis) {
        VaultWatcher watcher = new VaultWatcher();
        if (brain != null && semantic != null && brain.configured()) {
            try {
                semantic.syncVault(brain.allDocuments());
            } catch (RuntimeException ignore) {
                // A transient sync failure must never block startup; the poll loop retries.
            }
        }
        if (brain == null || semantic == null || brain.root() == null) {
            return watcher;   // nothing to watch; return an idle handle
        }
        Thread t = new Thread(() -> watcher.loop(brain, semantic, Math.max(500L, pollMillis)),
                "jarvis-vault-watch");
        t.setDaemon(true);
        watcher.thread = t;
        t.start();
        return watcher;
    }

    private void loop(BrainVault brain, SemanticMemoryService semantic, long pollMillis) {
        long last = safeFingerprint(brain);
        while (running) {
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            long now = safeFingerprint(brain);
            if (now != last) {
                last = now;
                try {
                    brain.reindexNow();
                    semantic.syncVault(brain.allDocuments());
                } catch (RuntimeException ignore) {
                    // Keep watching even if one resync fails (e.g. a note edited mid-read).
                }
            }
        }
    }

    private static long safeFingerprint(BrainVault brain) {
        try {
            return brain.fingerprint();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }
}
