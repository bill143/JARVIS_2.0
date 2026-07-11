package com.jarvis.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.RiskTier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManifestLoaderTest {

    @Test
    void parsesNameDescriptionRiskTierAndParameters() {
        ToolManifest m = ManifestLoader.parse("""
                {"name":"email_send","description":"Send an email","riskTier":"DESTRUCTIVE",
                 "parameters":[{"name":"to","type":"string","required":true,"description":"recipient"},
                               {"name":"body","required":false}]}""");
        assertEquals("email_send", m.name());
        assertEquals(RiskTier.DESTRUCTIVE, m.riskTier());
        assertEquals(2, m.parameters().size());
        assertEquals("to", m.parameters().get(0).name());
        assertTrue(m.parameters().get(0).required());
        assertEquals("string", m.parameters().get(1).type());   // defaulted
    }

    @Test
    void unknownRiskTierFallsBackToUnknownRatherThanThrowing() {
        ToolManifest m = ManifestLoader.parse("{\"name\":\"x\",\"riskTier\":\"NUCLEAR\"}");
        assertEquals(RiskTier.UNKNOWN, m.riskTier());
    }

    @Test
    void lowercaseRiskTierIsAccepted() {
        assertEquals(RiskTier.READ_ONLY,
                ManifestLoader.parse("{\"name\":\"clock\",\"riskTier\":\"read_only\"}").riskTier());
    }

    @Test
    void missingNameIsRejectedForSingleButSkippedInArray() {
        assertThrows(IllegalArgumentException.class,
                () -> ManifestLoader.parse("{\"riskTier\":\"MUTATING\"}"));
        List<ToolManifest> arr = ManifestLoader.parseArray("""
                [{"name":"good","riskTier":"READ_ONLY"},{"riskTier":"MUTATING"}]""");
        assertEquals(1, arr.size());          // the nameless one is skipped
        assertEquals("good", arr.get(0).name());
    }

    @Test
    void loadDirectoryReadsToolJsonFilesAndIgnoresMissingDir(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("clock.tool.json"),
                "{\"name\":\"clock\",\"riskTier\":\"READ_ONLY\"}");
        Files.writeString(dir.resolve("notes.txt"), "ignored");   // not a *.tool.json
        List<ToolManifest> loaded = ManifestLoader.loadDirectory(dir);
        assertEquals(1, loaded.size());
        assertEquals("clock", loaded.get(0).name());

        assertTrue(ManifestLoader.loadDirectory(dir.resolve("does-not-exist")).isEmpty());
    }
}
