package com.devmind.ingestion;

/**
 * Why a source couldn't be opened. Each problem has a stable kebab-case {@link #code()} and a fixed, user-safe {@link
 * #detail()} that never contains paths, URLs or repository content, so both can be stored as a job's error (ERR-5).
 */
public enum SourceProblem {
    /** The local path isn't under an allowed root, or escapes one through a symlink, gitfile or alternates (SEC-2). */
    LOCATION_NOT_ALLOWED("source-location-not-allowed", false, "The source location isn't allowed."),
    /** The local path is under an allowed root but doesn't exist, or the remote repository doesn't exist. */
    LOCATION_NOT_FOUND("source-location-not-found", false, "The source location doesn't exist."),
    /** The local path is a directory but not a Git repository. */
    NOT_A_REPOSITORY("source-not-a-repository", false, "The source location isn't a Git repository."),
    /** The remote URL breaks the HTTPS / host allow-list rules (SEC-3). */
    URL_NOT_ALLOWED("source-url-not-allowed", false, "The repository URL isn't allowed."),
    /** The ref isn't a well-formed branch or tag name or commit SHA. */
    INVALID_REF("ref-invalid", false, "The ref isn't a valid branch, tag or commit SHA."),
    /** No branch, tag or commit matches the ref. */
    REF_NOT_FOUND("ref-not-found", false, "No commit matches the ref."),
    /** The commit SHA prefix matches more than one object. */
    REF_AMBIGUOUS("ref-ambiguous", false, "The commit SHA prefix matches more than one object."),
    /** The clone grew past the configured size limit (SEC-3). */
    TOO_LARGE("source-too-large", false, "The repository is larger than the size limit."),
    /** The commit has more entries than the configured file limit (SEC-3). */
    TOO_MANY_FILES("source-too-many-files", false, "The commit has more files than the limit."),
    /** Cloning took longer than the configured timeout (ERR-6). */
    TIMED_OUT("source-timeout", true, "Reading the repository took too long."),
    /** The repository couldn't be reached or read; may succeed later. */
    UNREACHABLE("source-unreachable", true, "The repository couldn't be read.");

    private final String code;
    private final boolean retryable;
    private final String detail;

    SourceProblem(String code, boolean retryable, String detail) {
        this.code = code;
        this.retryable = retryable;
        this.detail = detail;
    }

    /** Returns the stable kebab-case code, e.g. {@code source-location-not-allowed}. */
    public String code() {
        return code;
    }

    /** Returns whether trying again later may succeed; false for invalid input and limits (ERR-3). */
    public boolean retryable() {
        return retryable;
    }

    /** Returns a fixed, user-safe sentence describing the problem. */
    public String detail() {
        return detail;
    }
}
