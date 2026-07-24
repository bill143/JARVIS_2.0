package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.rag.EmbeddingProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards Stage A req #3: the vault syncs into the unified store automatically — at startup and when
 * files change — with no manual "Connect" click. Tests the deterministic units (fingerprint change
 * detection, in-place reindex, startup mirror) rather than the poll thread's timing.
 */
class VaultAutoSyncTest {

    private static SemanticMemoryService store() {
        return new SemanticMemoryService(new InMemoryRecordStore(), EmbeddingProvider.DORMANT, null);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void fingerprintChangesWhenAFileIsAdded(@TempDir Path vault) throws Exception {
        write(vault.resolve("A.md"), "# A\nalpha");
        BrainVault brain = BrainVault.fromConfig(vault.toString(), true, null);
        long before = brain.fingerprint();
        write(vault.resolve("B.md"), "# B\nbeta");
        assertNotEquals(before, brain.fingerprint(), "adding a note must change the fingerprint");
    }

    @Test
    void reindexNowPicksUpNewNotesInPlace(@TempDir Path vault) throws Exception {
        write(vault.resolve("A.md"), "# A\nalpha content");
        BrainVault brain = BrainVault.fromConfig(vault.toString(), true, null);
        assertEquals(1, brain.count());

        write(vault.resolve("B.md"), "# B\nturbocharger notes");
        brain.reindexNow();
        assertEquals(2, brain.count());
        assertTrue(brain.search("turbocharger", 5).stream()
                .anyMatch(h -> h.path().equals("B.md")), "new note must be searchable after reindex");
    }

    @Test
    void watcherMirrorsTheVaultIntoTheStoreAtStartup(@TempDir Path vault) throws Exception {
        write(vault.resolve("Plan.md"), "# Plan\nriverfront marina schedule");
        BrainVault brain = BrainVault.fromConfig(vault.toString(), true, null);
        SemanticMemoryService semantic = store();
        assertTrue(semantic.all().isEmpty());

        VaultWatcher watcher = VaultWatcher.start(brain, semantic, 5_000L);
        try {
            // The initial mirror is synchronous inside start(), so this needs no thread wait.
            assertTrue(semantic.all().stream().anyMatch(d -> d.id().equals("vault-Plan.md")),
                    "startup sync should have mirrored the vault note");
            assertTrue(semantic.all().stream().anyMatch(
                    d -> "vault".equals(SemanticMemoryService.sourceOf(d))));
        } finally {
            watcher.close();
        }
    }
}
