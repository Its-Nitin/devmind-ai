package com.devmind.job;

import java.util.Objects;

/**
 * The result of one attempt, returned by {@link JobHandler#handle(JobContext)} (JAVA-7, ERR-2).
 *
 * <p>Failure codes and details are stored on the job and shown to its owner (ERR-5): the code is stable kebab-case,
 * the detail is a short user-safe sentence with no stack traces, source code, paths or secrets.
 */
public sealed interface JobOutcome {

    /** Longest failure detail accepted, in characters. */
    int MAX_DETAIL_LENGTH = 500;

    /** The job is done. */
    record Succeeded() implements JobOutcome {}

    /** A failed attempt; {@link #code()} and {@link #detail()} are stored on the job. */
    sealed interface Failure extends JobOutcome {

        /** Returns the stable kebab-case failure code, never null. */
        String code();

        /** Returns the user-safe description, 1 to {@value #MAX_DETAIL_LENGTH} characters. */
        String detail();
    }

    /** A transient failure (I/O, timeout): run again after a backoff while attempts remain, otherwise fail (ERR-3). */
    record RetryableFailure(String code, String detail) implements Failure {

        /** @throws IllegalArgumentException if the code isn't kebab-case or the detail is blank or too long */
        public RetryableFailure {
            requireValidFailure(code, detail);
        }
    }

    /** A failure that retrying can't fix, e.g. invalid input: fail now (ERR-3). */
    record PermanentFailure(String code, String detail) implements Failure {

        /** @throws IllegalArgumentException if the code isn't kebab-case or the detail is blank or too long */
        public PermanentFailure {
            requireValidFailure(code, detail);
        }
    }

    /** Returns {@link Succeeded}. */
    static JobOutcome succeeded() {
        return new Succeeded();
    }

    /** Returns a {@link RetryableFailure}. */
    static JobOutcome retryable(String code, String detail) {
        return new RetryableFailure(code, detail);
    }

    /** Returns a {@link PermanentFailure}. */
    static JobOutcome permanent(String code, String detail) {
        return new PermanentFailure(code, detail);
    }

    private static void requireValidFailure(String code, String detail) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (!code.matches("[a-z0-9]+(-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("code must be kebab-case: " + code);
        }
        if (detail.isBlank() || detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail must be 1 to " + MAX_DETAIL_LENGTH + " characters");
        }
    }
}
