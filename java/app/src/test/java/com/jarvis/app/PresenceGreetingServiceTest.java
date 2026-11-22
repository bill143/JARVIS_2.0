package com.jarvis.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PresenceGreetingServiceTest {

    private final PresenceGreetingService service = new PresenceGreetingService();

    private static PeopleStore.Person person(String name, String greetingName) {
        return new PeopleStore.Person("p1", name, "friend", "", "", "", "", "", List.of(), "",
                greetingName);
    }

    @Test
    void knownGreetingUsesGreetingNameWhenSet() {
        PeopleStore.Person p = person("Richard Stanczak", "Rich");
        assertEquals("Hi Rich, how are you?", service.knownGreeting(p));
    }

    @Test
    void knownGreetingFallsBackToNameWhenGreetingNameIsBlank() {
        PeopleStore.Person p = person("Jennifer", "");
        assertEquals("Hi Jennifer, how are you?", service.knownGreeting(p));
    }

    @Test
    void unknownGreetingAsksForAName() {
        assertEquals("Hi, my name is Jarvis, what is your name?", service.unknownGreeting());
    }
}
