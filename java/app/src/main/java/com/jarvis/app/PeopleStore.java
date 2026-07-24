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
import java.util.Optional;

/**
 * Local, private store of known people (name, relationship, notes, and a reference photo as a data
 * URL). Persisted as a single JSON file under {@code ~/.jarvis/people.json} — photos never leave
 * the machine except when sent to the vision model for on-demand recognition.
 *
 * <p>Also backs the vision motion + face-recognition feature: {@code faceSubjects} holds the
 * CompreFace subject/image ids enrolled for this person (a person may accumulate more than one
 * over time), {@code lastSeenAt} is the ISO-8601 instant of their most recent recognized sighting
 * (empty if never), and {@code greetingName} is an optional preferred name to use in greetings
 * (empty means "use {@code name}").
 */
public final class PeopleStore {

    /** One known person. {@code photo} is a data URL (e.g. {@code data:image/png;base64,...}). */
    public record Person(String id, String name, String relationship, String email, String phone,
            String company, String notes, String photo, List<String> faceSubjects,
            String lastSeenAt, String greetingName) {
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
                List<String> faceSubjects = new ArrayList<>();
                JsonNode faceSubjectsNode = n.path("faceSubjects");
                if (faceSubjectsNode.isArray()) {
                    for (JsonNode s : faceSubjectsNode) {
                        faceSubjects.add(s.asText(""));
                    }
                }
                people.add(new Person(n.path("id").asText(), n.path("name").asText(),
                        n.path("relationship").asText(""), n.path("email").asText(""),
                        n.path("phone").asText(""), n.path("company").asText(""),
                        n.path("notes").asText(""), n.path("photo").asText(""),
                        List.copyOf(faceSubjects), n.path("lastSeenAt").asText(""),
                        n.path("greetingName").asText("")));
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
                nz(notes), nz(photo), List.of(), "", ""));
        persist(people);
        return id;
    }

    /**
     * Updates the person with {@code id} in place, keeping their id. A blank {@code photo} leaves
     * the existing photo untouched (so an edit that doesn't re-upload keeps recognition working).
     *
     * @return {@code true} if a person with that id existed and was updated
     */
    public synchronized boolean update(String id, String name, String relationship, String email,
            String phone, String company, String notes, String photo) {
        List<Person> people = all();
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id().equals(id)) {
                Person existing = people.get(i);
                String keepPhoto = (photo == null || photo.isBlank()) ? existing.photo() : photo;
                people.set(i, new Person(id, name, nz(relationship), nz(email), nz(phone),
                        nz(company), nz(notes), nz(keepPhoto), existing.faceSubjects(),
                        existing.lastSeenAt(), existing.greetingName()));
                persist(people);
                return true;
            }
        }
        return false;
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

    /** The first person whose {@code faceSubjects} contains {@code subjectId}, if any. */
    public synchronized Optional<Person> findByFaceSubject(String subjectId) {
        if (subjectId == null) {
            return Optional.empty();
        }
        return all().stream().filter(p -> p.faceSubjects().contains(subjectId)).findFirst();
    }

    /** The person whose {@code name} matches {@code name} case-insensitively, if any. */
    public synchronized Optional<Person> findByNameIgnoreCase(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return all().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * Updates just {@code lastSeenAt} for the person with {@code id}, preserving every other field.
     *
     * @return {@code true} if a person with that id existed and was updated
     */
    public synchronized boolean recordSighting(String id, String lastSeenAt) {
        List<Person> people = all();
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id().equals(id)) {
                Person existing = people.get(i);
                people.set(i, new Person(existing.id(), existing.name(), existing.relationship(),
                        existing.email(), existing.phone(), existing.company(), existing.notes(),
                        existing.photo(), existing.faceSubjects(), nz(lastSeenAt),
                        existing.greetingName()));
                persist(people);
                return true;
            }
        }
        return false;
    }

    /**
     * Appends {@code subjectId} to the {@code faceSubjects} of the person with {@code id} (no
     * duplicates) and, if their current {@code greetingName} is blank, sets it to
     * {@code greetingNameIfBlank}.
     *
     * @return {@code true} if a person with that id existed and was updated
     */
    public synchronized boolean enrollFaceSubject(String id, String subjectId,
            String greetingNameIfBlank) {
        List<Person> people = all();
        for (int i = 0; i < people.size(); i++) {
            if (people.get(i).id().equals(id)) {
                Person existing = people.get(i);
                List<String> subjects = existing.faceSubjects();
                List<String> updatedSubjects = subjects.contains(subjectId)
                        ? subjects
                        : concat(subjects, subjectId);
                String greetingName = existing.greetingName().isBlank()
                        ? nz(greetingNameIfBlank)
                        : existing.greetingName();
                people.set(i, new Person(existing.id(), existing.name(), existing.relationship(),
                        existing.email(), existing.phone(), existing.company(), existing.notes(),
                        existing.photo(), updatedSubjects, existing.lastSeenAt(), greetingName));
                persist(people);
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a brand-new person with no contact info beyond a name, a single enrolled face
     * subject, and a greeting name — for enrolling a visitor JARVIS has never met via the vision
     * pipeline.
     *
     * @return the assigned id
     */
    public synchronized String addWithFaceSubject(String name, String subjectId,
            String greetingName) {
        List<Person> people = all();
        String id = "p" + (people.size() + 1) + "-" + Integer.toHexString(name.hashCode() & 0xffff);
        people.add(new Person(id, name, "", "", "", "", "", "", List.of(nz(subjectId)),
                "", nz(greetingName)));
        persist(people);
        return id;
    }

    private static List<String> concat(List<String> existing, String next) {
        List<String> combined = new ArrayList<>(existing);
        combined.add(next);
        return List.copyOf(combined);
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
                ArrayNode faceSubjects = o.putArray("faceSubjects");
                for (String subjectId : p.faceSubjects()) {
                    faceSubjects.add(subjectId);
                }
                o.put("lastSeenAt", p.lastSeenAt());
                o.put("greetingName", p.greetingName());
            }
            Files.writeString(file, arr.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write people file", e);
        }
    }
}
