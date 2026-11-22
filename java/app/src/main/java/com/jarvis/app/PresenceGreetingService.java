package com.jarvis.app;

/**
 * Builds the short greeting JARVIS speaks when a motion event resolves to a known or unknown
 * person. Deliberately stateless — no collaborators, no persistence — so it's trivial to unit test
 * and to call from {@link MotionEventService}.
 */
final class PresenceGreetingService {

    /** Greeting for a recognized person, preferring their {@code greetingName} when set. */
    String knownGreeting(PeopleStore.Person person) {
        String displayName = person.greetingName() == null || person.greetingName().isBlank()
                ? person.name()
                : person.greetingName();
        return "Hi " + displayName + ", how are you?";
    }

    /** Greeting for an unrecognized visitor. */
    String unknownGreeting() {
        return "Hi, my name is Jarvis, what is your name?";
    }
}
