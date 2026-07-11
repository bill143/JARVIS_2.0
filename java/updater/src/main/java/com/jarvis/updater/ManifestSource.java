package com.jarvis.updater;

import java.io.IOException;

/**
 * Where the manifest JSON comes from. A seam so the check is testable without a network — the
 * production source ({@link HttpManifestSource}) fetches over HTTPS; tests supply a lambda.
 */
@FunctionalInterface
public interface ManifestSource {

    /** Returns the raw manifest JSON, or throws if it can't be fetched. */
    String fetch() throws IOException;
}
