package com.jarvis.updater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void parsesAndIgnoresSuffixes() {
        assertEquals(new Version(1, 2, 3), Version.parse("1.2.3"));
        assertEquals(new Version(0, 1, 0), Version.parse("0.1.0-SNAPSHOT"));
        assertEquals(new Version(2, 0, 0), Version.parse("2.0.0+build.7"));
        assertEquals(new Version(1, 0, 0), Version.parse("1"));   // missing components default to 0
    }

    @Test
    void comparesBySignificance() {
        assertTrue(Version.parse("0.2.0").isNewerThan(Version.parse("0.1.9")));
        assertTrue(Version.parse("1.0.0").isNewerThan(Version.parse("0.9.9")));
        assertTrue(Version.parse("0.1.2").isNewerThan(Version.parse("0.1.1")));
        assertFalse(Version.parse("0.1.0").isNewerThan(Version.parse("0.1.0")));
        assertFalse(Version.parse("0.1.0").isNewerThan(Version.parse("0.2.0")));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("not.a.version"));
    }
}
