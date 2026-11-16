package com.devmind.ingestion.internal.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A ref as the user wrote it, checked to be a plain branch or tag name or a hex commit SHA. Resolution looks up exactly
 * {@code refs/heads/<value>}, {@code refs/tags/<value>} and, if {@link #mayBeSha()}, a commit with that SHA prefix,
 * so revision expressions such as {@code main~1}, {@code HEAD@{1}} or {@code :/text} are never evaluated.
 *
 * <p>Deliberately stricter than Git: ASCII letters, digits, {@code . _ / -}, with Git's own rules on top (no {@code
 * ..}, no empty or dot-leading segment, no {@code .lock} suffix). That covers the branch and tag names real projects
 * use.
 *
 * @param value the validated name
 */
public record RefName(String value) {

    /** Longer refs are rejected; no real branch, tag or SHA comes close. */
    public static final int MAX_LENGTH = 200;

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final Pattern SHA_PREFIX = Pattern.compile("[0-9a-f]{7,40}");

    /** @throws IllegalArgumentException if {@code value} isn't valid; use {@link #parse} for untrusted input */
    public RefName {
        if (!isValid(value)) {
            throw new IllegalArgumentException("invalid ref");
        }
    }

    /** Returns the ref, or empty if {@code ref} isn't a valid branch or tag name or SHA. */
    public static Optional<RefName> parse(String ref) {
        return isValid(ref) ? Optional.of(new RefName(ref)) : Optional.empty();
    }

    /** Returns whether the value could be a commit SHA or SHA prefix (7-40 lowercase hex characters). */
    public boolean mayBeSha() {
        return SHA_PREFIX.matcher(value).matches();
    }

    private static boolean isValid(String ref) {
        if (ref.length() > MAX_LENGTH || !ALLOWED.matcher(ref).matches()) {
            return false;
        }
        if (ref.contains("..") || ref.endsWith(".lock") || ref.endsWith("/")) {
            return false;
        }
        for (String segment : ref.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return false;
            }
        }
        return true;
    }
}
