package com.devmind.indexing.internal.application;

import com.devmind.ingestion.ProjectSource;
import com.devmind.project.SourceType;
import java.util.Map;
import java.util.Optional;

/**
 * An INDEX job's payload: what to read, captured when the job is submitted, after the owner check (SEC-1). The source
 * is copied from the project then, so the handler, which runs without a user, needs no project lookup. Projects can't
 * be edited in v1, so the copy can't go stale.
 *
 * @param ref what to index: the resolved commit SHA for a local source; for a remote one the ref as given, so the
 *     clone stays shallow
 */
record IndexRequest(String ref, ProjectSource source) {

    private static final String REF = "ref";
    private static final String SOURCE_TYPE = "sourceType";
    private static final String SOURCE_LOCATION = "sourceLocation";

    Map<String, Object> toPayload() {
        return Map.of(REF, ref, SOURCE_TYPE, source.type().name(), SOURCE_LOCATION, source.location());
    }

    /** Reads a payload written by {@link #toPayload()}; empty if it's malformed. */
    static Optional<IndexRequest> fromPayload(Map<String, Object> payload) {
        if (payload.get(REF) instanceof String ref
                && payload.get(SOURCE_TYPE) instanceof String type
                && payload.get(SOURCE_LOCATION) instanceof String location) {
            try {
                return Optional.of(new IndexRequest(ref, new ProjectSource(SourceType.valueOf(type), location)));
            } catch (IllegalArgumentException ex) {
                return Optional.empty(); // an unknown source type
            }
        }
        return Optional.empty();
    }
}
