package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Guards Stage E: natural-language "remember that…" capture, without swallowing real questions. */
class RecallCaptureTest {

    @Test
    void capturesTheFactAfterRememberThat() {
        var f = RecallCapture.parse("remember that the gate code is 4417.");
        assertTrue(f.isPresent());
        assertEquals("the gate code is 4417", f.get().content());
        assertTrue(f.get().title().startsWith("The gate code"));
    }

    @Test
    void handlesVariousImperativeForms() {
        assertTrue(RecallCapture.parse("Remember to call the surveyor Monday").isPresent());
        assertTrue(RecallCapture.parse("note that the riverfront bid is due Friday").isPresent());
        assertTrue(RecallCapture.parse("Jarvis, please remember my wife's name is Dana").isPresent());
        assertTrue(RecallCapture.parse("make a note: order rebar").isPresent());
        assertEquals("order rebar",
                RecallCapture.parse("make a note: order rebar").get().content());
    }

    @Test
    void doesNotHijackGenuineQuestions() {
        assertFalse(RecallCapture.parse("do you remember when we met?").isPresent());
        assertFalse(RecallCapture.parse("what should I remember for the exam?").isPresent());
        assertFalse(RecallCapture.parse("who is the vendor for the marina?").isPresent());
        assertFalse(RecallCapture.parse("remember").isPresent());   // nothing to store
    }

    @Test
    void titleIsShortenedForLongFacts() {
        String longFact = "the project kickoff meeting with the city engineers is scheduled for the "
                + "second Tuesday of next month at the downtown office";
        var f = RecallCapture.parse("remember that " + longFact);
        assertTrue(f.isPresent());
        assertTrue(f.get().title().length() <= 52, f.get().title());
        assertTrue(f.get().title().endsWith("…"));
        assertTrue(f.get().content().contains("downtown office"));   // full text preserved
    }
}
