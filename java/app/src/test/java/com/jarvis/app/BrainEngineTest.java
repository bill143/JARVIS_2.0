package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryRecordStore;
import com.jarvis.rag.EmbeddingProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the redesigned Brain engine (#1): live connect, write-approval gate, and unified sync. */
class BrainEngineTest {

    private static BrainVault vault() {
        return BrainVault.fromConfig(null, false, null);   // starts unconfigured
    }

    @Test
    void connectsLiveIndexesNotesAndReconnectsWithoutRestart(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Alpha.md"), "# Alpha\nbrushless motor notes");
        Files.writeString(dir.resolve("Beta.md"), "# Beta\nbattery pack sizing");
        BrainVault b = vault();
        assertFalse(b.configured());

        assertTrue(b.connect(dir.toString(), false));
        assertTrue(b.configured());
        assertEquals(2, b.count());
        assertTrue(b.search("battery", 5).stream().anyMatch(h -> h.title().equals("Beta")));

        // Re-connect to an empty/invalid path live → unconfigured, no restart.
        assertFalse(b.connect("   ", false));
        assertFalse(b.configured());
        assertEquals(0, b.count());
    }

    @Test
    void readOnlyVaultRefusesWriteProposals(@TempDir Path dir) {
        BrainVault b = vault();
        b.connect(dir.toString(), false);   // writes NOT enabled
        assertTrue(b.readOnly());
        assertThrows(BrainVault.VaultAccessException.class,
                () -> b.proposeWrite("Note.md", "hello", "note"));
    }

    @Test
    void writeGatePersistsOnlyOnExplicitApproval(@TempDir Path dir) throws Exception {
        BrainVault b = vault();
        b.connect(dir.toString(), true);   // writes enabled
        assertFalse(b.readOnly());

        String id = b.proposeWrite("Ideas/New.md", "# New\nan idea", "note");
        // Nothing on disk yet — proposal is pending.
        assertFalse(Files.exists(dir.resolve("Ideas/New.md")));
        assertTrue(b.pendingWrites().stream().anyMatch(w -> w.id().equals(id)));

        // Approve → the file is written, indexed, and the proposal clears.
        assertTrue(b.approveWrite(id));
        assertTrue(Files.exists(dir.resolve("Ideas/New.md")));
        assertEquals("# New\nan idea", Files.readString(dir.resolve("Ideas/New.md")));
        assertTrue(b.pendingWrites().isEmpty());
        assertTrue(b.search("idea", 5).size() >= 1);
    }

    @Test
    void rejectDiscardsWithoutWriting(@TempDir Path dir) throws Exception {
        BrainVault b = vault();
        b.connect(dir.toString(), true);
        String id = b.proposeWrite("Draft.md", "junk", "note");
        assertTrue(b.rejectWrite(id));
        assertFalse(Files.exists(dir.resolve("Draft.md")));
        assertTrue(b.pendingWrites().isEmpty());
    }

    @Test
    void writeProposalsCannotEscapeTheVault(@TempDir Path dir) {
        BrainVault b = vault();
        b.connect(dir.toString(), true);
        assertThrows(BrainVault.VaultAccessException.class,
                () -> b.proposeWrite("../outside.md", "x", "note"));
        assertThrows(BrainVault.VaultAccessException.class,
                () -> b.proposeWrite("nested/../../escape.md", "x", "note"));
    }

    @Test
    void vaultNotesUnifyIntoTheSemanticStoreAndAreRecallable(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Gearbox.md"), "# Gearbox\nplanetary gear ratios");
        BrainVault b = vault();
        b.connect(dir.toString(), false);
        SemanticMemoryService semantic = new SemanticMemoryService(
                new InMemoryRecordStore(), EmbeddingProvider.DORMANT, null);

        int n = semantic.syncVault(b.allDocuments());
        assertEquals(1, n);
        assertTrue(semantic.all().stream().anyMatch(d -> d.id().equals("vault-Gearbox.md")));
        assertTrue(semantic.recall("planetary gear", 5).stream()
                .anyMatch(s -> s.document().content().contains("planetary")));

        // Removing the note from disk and re-syncing forgets it from the unified store.
        Files.delete(dir.resolve("Gearbox.md"));
        b.connect(dir.toString(), false);
        semantic.syncVault(b.allDocuments());
        assertFalse(semantic.all().stream().anyMatch(d -> d.id().equals("vault-Gearbox.md")));
    }
}
