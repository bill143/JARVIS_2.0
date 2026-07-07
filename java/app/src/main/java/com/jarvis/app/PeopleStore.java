package com.jarvis.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Local, private store of known people (name, relationship, notes, and a reference photo as a data
 * URL). Persisted as a single JSON file under {@code ~/.jarvis/people.json} — photos never leave
 * the machine except when sent to the vision model for on-demand recognition.
 */
public final class PeopleStore {

    /** One known person. {@code photo} is a data URL (e.g. {@code data:image/png;base64,...}). */
    public record Person(String id, String name, String relationship, String email, String phone,
            String company, String notes, String photo) {
    }

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public PeopleStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** All people, with photos (used for recognition). */
    public synchronized List<Person> all() {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            List<Person> people = new ArrayList<>();
            for (JsonNode n : mapper.readTree(Files.readString(file))) {
                people.add(new Person(n.path("id").asText(), n.path("name").asText(),
                        n.path("relationship").asText(""), n.path("email").asText(""),
                        n.path("phone").asText(""), n.path("company").asText(""),
                        n.path("notes").asText(""), n.path("photo").asText("")));
            }
            return people;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read people file", e);
        }
    }

    /** People metadata without photo bytes (for listing in the UI). */
    public synchronized ArrayNode summaries() {
        ArrayNode out = mapper.createArrayNode();
        for (Person p : all()) {
            ObjectNode o = out.addObject();
            o.put("id", p.id());
            o.put("name", p.name());
            o.put("relationship", p.relationship());
            o.put("email", p.email());
            o.put("phone", p.phone());
            o.put("company", p.company());
            o.put("notes", p.notes());
            o.put("hasPhoto", p.photo() != null && !p.photo().isBlank());
        }
        return out;
    }

    /** Adds a person and returns the assigned id. */
    public synchronized String add(String name, String relationship, String email, String phone,
            String company, String notes, String photo) {
        List<Person> people = all();
        String id = "p" + (people.size() + 1) + "-" + Integer.toHexString(name.hashCode() & 0xffff);
        people.add(new Person(id, name, nz(relationship), nz(email), nz(phone), nz(company),
                nz(notes), nz(photo)));
        persist(people);
        return id;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** A compact text block of contacts for the assistant's context (no photos). */
    public synchronized String contactsBlock() {
        StringBuilder sb = new StringBuilder();
        for (Person p : all()) {
            sb.append("- ").append(p.name());
            if (!p.relationship().isBlank()) {
                sb.append(" (").append(p.relationship()).append(")");
            }
            if (!p.email().isBlank()) {
                sb.append(", email ").append(p.email());
            }
            if (!p.phone().isBlank()) {
                sb.append(", phone ").append(p.phone());
            }
            if (!p.company().isBlank()) {
                sb.append(", ").append(p.company());
            }
            if (!p.notes().isBlank()) {
                sb.append(". ").append(p.notes());
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }

    /** Removes a person by id; returns whether one was removed. */
    public synchronized boolean delete(String id) {
        List<Person> people = all();
        boolean removed = people.removeIf(p -> p.id().equals(id));
        if (removed) {
            persist(people);
        }
        return removed;
    }

    private void persist(List<Person> people) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            ArrayNode arr = mapper.createArrayNode();
            for (Person p : people) {
                ObjectNode o = arr.addObject();
                o.put("id", p.id());
                o.put("name", p.name());
                o.put("relationship", p.relationship());
                o.put("email", p.email());
                o.put("phone", p.phone());
                o.put("company", p.company());
                o.put("notes", p.notes());
                o.put("photo", p.photo());
            }
            Files.writeString(file, arr.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write people file", e);
        }
    }
}
