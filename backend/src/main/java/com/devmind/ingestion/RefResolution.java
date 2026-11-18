package com.devmind.ingestion;

import java.util.Objects;
import java.util.regex.Pattern;

/** The result of {@link SourceSnapshotProvider#resolve}: the commit a ref names now, or why it can't be resolved. */
public sealed interface RefResolution {

    /**
     * The ref resolved.
     *
     * @param commitSha 40 lowercase hex characters; for a remote SHA prefix, which only a fetch could expand, the
     *     prefix as given (7 to 39 lowercase hex characters)
     */
    record Resolved(String commitSha) implements RefResolution {

        private static final Pattern SHA_OR_PREFIX = Pattern.compile("[0-9a-f]{7,40}");

        /** @throws IllegalArgumentException if {@code commitSha} isn't a lowercase hex SHA or prefix */
        public Resolved {
            Objects.requireNonNull(commitSha, "commitSha");
            if (!SHA_OR_PREFIX.matcher(commitSha).matches()) {
                throw new IllegalArgumentException("commitSha must be 7 to 40 lowercase hex characters");
            }
        }
    }

    /**
     * The ref or its source was rejected, or the source couldn't be reached.
     *
     * @param problem why, never null
     */
    record Failed(SourceProblem problem) implements RefResolution {

        /** @throws NullPointerException if {@code problem} is null */
        public Failed {
            Objects.requireNonNull(problem, "problem");
        }
    }
}
