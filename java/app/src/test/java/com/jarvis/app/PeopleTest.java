package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeopleTest {

    @TempDir
    Path dir;

    private PeopleStore store() {
        return new PeopleStore(dir.resolve("people.json"));
    }

    @Test
    void peopleSurviveReloadWithContactFields() {
        PeopleStore a = store();
        a.add("Jennifer", "wife", "jen@x.com", "555-1000", "Home", "loves gardening",
                "data:image/png;base64,AAAA");
        a.add("Sam", "coworker", "", "", "", "", "");

        PeopleStore b = store();   // fresh instance over same file
        List<PeopleStore.Person> all = b.all();
        assertEquals(2, all.size());
        assertEquals("Jennifer", all.get(0).name());
        assertEquals("jen@x.com", all.get(0).email());
        assertEquals("555-1000", all.get(0).phone());
        assertTrue(b.summaries().get(0).path("hasPhoto").asBoolean());
        assertEquals("jen@x.com", b.summaries().get(0).path("email").asText());
        assertFalse(b.summaries().get(1).path("hasPhoto").asBoolean());
    }

    @Test
    void deleteRemovesAPerson() {
        PeopleStore s = store();
        String id = s.add("Bob", "friend", "", "", "", "", "");
        assertTrue(s.delete(id));
        assertFalse(s.delete(id));
        assertTrue(s.all().isEmpty());
    }

    @Test
    void summariesOmitPhotoBytes() {
        PeopleStore s = store();
        s.add("Ann", "sister", "", "", "", "", "data:image/png;base64,ZZZZ");
        assertFalse(s.summaries().get(0).has("photo"));   // no base64 leaked into the list
    }

    @Test
    void contactsBlockIsUsableByTheAssistant() {
        PeopleStore s = store();
        s.add("Jennifer", "wife", "jen@x.com", "555-1000", "", "birthday in May", "");
        String block = s.contactsBlock();
        assertTrue(block.contains("Jennifer"));
        assertTrue(block.contains("jen@x.com"));
        assertTrue(block.contains("555-1000"));
        assertTrue(block.contains("birthday in May"));
    }

    @Test
    void recognizerRequestIncludesLiveImageAndKnownPeople() {
        PeopleRecognizer rec = new PeopleRecognizer(req -> "{}", "test-model");
        List<PeopleStore.Person> people = List.of(
                new PeopleStore.Person("p1", "Jennifer", "wife", "jen@x.com", "555", "Home",
                        "gardener", "data:image/jpeg;base64,AAAA"));
        String request = rec.buildRequest("data:image/png;base64,LIVE", people);

        assertTrue(request.contains("\"model\":\"test-model\""));
        assertTrue(request.contains("LIVE"));                 // live capture
        assertTrue(request.contains("AAAA"));                 // reference photo
        assertTrue(request.contains("image/jpeg"));           // reference media type preserved
        assertTrue(request.contains("Jennifer"));
        assertTrue(request.contains("LIVE webcam capture"));
    }

    @Test
    void recognizerReturnsModelText() throws Exception {
        PeopleRecognizer rec = new PeopleRecognizer(
                req -> "{\"content\":[{\"type\":\"text\",\"text\":\"That's Jennifer, your wife, sir.\"}]}",
                "m");
        String out = rec.recognize("data:image/png;base64,LIVE",
                List.of(new PeopleStore.Person("p1", "Jennifer", "wife", "", "", "", "",
                        "data:image/png;base64,AAAA")));
        assertEquals("That's Jennifer, your wife, sir.", out);
    }
}
