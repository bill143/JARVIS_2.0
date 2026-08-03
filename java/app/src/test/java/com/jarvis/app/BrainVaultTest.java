package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.audit.AuditEntry;
import com.jarvis.audit.AuditLog;
import com.jarvis.audit.AuditQuery;
import com.jarvis.audit.RecordStoreAuditLog;
import com.jarvis.memory.InMemoryRecordStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BrainVaultTest {

    private AuditLog newAudit() {
        return new RecordStoreAuditLog(new InMemoryRecordStore());
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void unconfiguredWhenPathBlankOrMissing() {
        assertFalse(BrainVault.fromConfig(null, true, null).configured());
        assertFalse(BrainVault.fromConfig("   ", true, null).configured());
        assertFalse(BrainVault.fromConfig("/no/such/vault/here", true, null).configured());
    }

    @Test
    void indexesMarkdownRecursivelySkippingHiddenAndNonMarkdown(@TempDir Path vault) throws Exception {
        write(vault.resolve("Welcome.md"), "# Welcome\nbrushless motor notes");
        write(vault.resolve("projects/Go-kart.md"), "# Go-kart\nbattery and motor");
        write(vault.resolve("notes.txt"), "not markdown, ignore me");
        write(vault.resolve(".obsidian/config.md"), "# hidden\nshould be skipped");

        BrainVault v = BrainVault.fromConfig(vault.toString(), true, null);
        assertTrue(v.configured());
        assertTrue(v.readOnly());
        assertEquals(2, v.count());                       // txt + hidden excluded
        assertTrue(v.notes().stream().anyMatch(n -> n.path().equals("projects/Go-kart.md")));
        assertTrue(v.notes().stream().anyMatch(n -> "Welcome".equals(n.title())));   // H1 title
    }

    private Path vault() throws Exception {
        Path p = Files.createTempDirectory("brain-search");
        write(p.resolve("Go-kart.md"), "# Go-kart\nbrushless motor and battery pack");
        write(p.resolve("Recipes.md"), "# Recipes\npasta and tomato sauce");
        return p;
    }

    @Test
    void searchRanksRelevantNoteFirst() throws Exception {
        BrainVault v = BrainVault.fromConfig(vault().toString(), true, null);
        List<BrainVault.Hit> hits = v.search("brushless battery", 5);
        assertFalse(hits.isEmpty());
        assertEquals("Go-kart.md", hits.get(0).path());
        assertFalse(hits.get(0).snippet().isBlank());
    }

    @Test
    void readNoteReturnsTitleAndRawMarkdown(@TempDir Path vault) throws Exception {
        write(vault.resolve("sub/Note.md"), "# My Title\nbody text here");
        BrainVault v = BrainVault.fromConfig(vault.toString(), true, null);
        String[] note = v.readNote("sub/Note.md");
        assertEquals("My Title", note[0]);
        assertTrue(note[1].contains("body text here"));
    }

    @Test
    void rejectsParentTraversal(@TempDir Path parent) throws Exception {
        Path vault = parent.resolve("vault");
        write(vault.resolve("ok.md"), "# ok\nfine");
        write(parent.resolve("secret.md"), "# secret\ntop secret outside the vault");
        BrainVault v = BrainVault.fromConfig(vault.toString(), true, null);

        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote("../secret.md"));
        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote("sub/../../secret.md"));
    }

    @Test
    void rejectsAbsolutePathsAndNonMarkdown(@TempDir Path vault) throws Exception {
        write(vault.resolve("ok.md"), "# ok\nfine");
        write(vault.resolve("data.txt"), "plain");
        BrainVault v = BrainVault.fromConfig(vault.toString(), true, null);

        assertThrows(BrainVault.VaultAccessException.class,
                () -> v.readNote(vault.resolve("ok.md").toAbsolutePath().toString()));
        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote("data.txt"));
        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote("missing.md"));
        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote(""));
    }

    @Test
    void rejectsSymlinkEscape(@TempDir Path parent) throws Exception {
        Path vault = parent.resolve("vault");
        Files.createDirectories(vault);
        Path outside = parent.resolve("outside");
        write(outside.resolve("secret.md"), "# secret\nescape target");
        Path link = vault.resolve("escape.md");
        try {
            Files.createSymbolicLink(link, outside.resolve("secret.md"));
        } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
            return;   // filesystem doesn't support symlinks — nothing to test here
        }
        BrainVault v = BrainVault.fromConfig(vault.toString(), true, null);
        assertThrows(BrainVault.VaultAccessException.class, () -> v.readNote("escape.md"));
    }

    @Test
    void auditsSearchAndNoteView(@TempDir Path vault) throws Exception {
        write(vault.resolve("Note.md"), "# Note\nsearchable content");
        AuditLog audit = newAudit();
        BrainVault v = BrainVault.fromConfig(vault.toString(), true, audit);

        v.search("searchable", 5);
        v.readNote("Note.md");

        List<AuditEntry> entries = audit.query(AuditQuery.all());
        assertTrue(entries.stream().anyMatch(e -> e.event().action().equals("brain_search")));
        assertTrue(entries.stream().anyMatch(e -> e.event().action().equals("brain_note_view")));
    }
}
