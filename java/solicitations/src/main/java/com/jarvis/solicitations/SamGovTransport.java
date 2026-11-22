package com.jarvis.solicitations;

import java.io.IOException;
import java.util.Map;

/**
 * Transport seam for the SAM.gov Opportunities API v2 ({@code api.sam.gov/opportunities/v2/search}).
 * The adapter supplies semantic query params (NAICS, state, set-aside, title, limit); the concrete
 * transport is responsible for the mechanics the adapter must stay clock-free about: the API key and
 * the required {@code postedFrom}/{@code postedTo} window. Unit tests inject a fake that returns a
 * canned response, so normalization is verified with no key and no network.
 */
public interface SamGovTransport {

    /** Whether a key is configured for live calls. */
    boolean available();

    /**
     * Runs a search and returns the raw JSON body.
     *
     * @param params semantic query params from the adapter (e.g. {@code ncode}, {@code state},
     *     {@code typeOfSetAside}, {@code title}, {@code limit}, {@code noticeid})
     */
    String search(Map<String, String> params) throws IOException, InterruptedException;
}
