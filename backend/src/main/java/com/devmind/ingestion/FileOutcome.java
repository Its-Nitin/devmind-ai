package com.devmind.ingestion;

import java.util.Objects;

/** What {@link IngestionPipeline#process} made of one file (JAVA-7). */
public sealed interface FileOutcome {

    /**
     * The file is indexed.
     *
     * @param file never null
     */
    record Kept(NormalizedFile file) implements FileOutcome {

        /** @throws NullPointerException if {@code file} is null */
        public Kept {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * The file is not indexed.
     *
     * @param reason the first rule that excluded it
     */
    record Excluded(ExclusionReason reason) implements FileOutcome {

        /** @throws NullPointerException if {@code reason} is null */
        public Excluded {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
