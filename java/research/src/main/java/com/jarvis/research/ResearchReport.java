package com.jarvis.research;

import java.util.List;

/**
 * The output of a research run: the question, a synthesized answer, and the numbered {@link Source}
 * list the answer is grounded in. Sources are <b>always</b> present when any were found — the report
 * is citation-preserving by construction, even if the synthesizer's prose omits inline markers.
 *
 * @param question the original question
 * @param answer the synthesized, citation-bearing answer
 * @param sources the numbered sources (may be empty when nothing was found)
 */
public record ResearchReport(String question, String answer, List<Source> sources) {

    public ResearchReport {
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** Whether the answer is backed by at least one citation. */
    public boolean hasCitations() {
        return !sources.isEmpty();
    }
}
