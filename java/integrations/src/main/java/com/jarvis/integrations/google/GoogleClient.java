package com.jarvis.integrations.google;

import java.io.IOException;

/** Authorized Google REST access: GET and JSON POST with a Bearer token attached. Seam for tests. */
public interface GoogleClient {

    String get(String url) throws IOException, InterruptedException;

    String postJson(String url, String jsonBody) throws IOException, InterruptedException;
}
