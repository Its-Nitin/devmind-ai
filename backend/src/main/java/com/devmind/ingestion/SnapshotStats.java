package com.devmind.ingestion;

/**
 * What a snapshot read at its commit. Skipped entries are counted, never silently dropped (ERR-4).
 *
 * @param files regular files that {@link SourceSnapshot#files()} yields
 * @param symlinksSkipped symlink entries; their targets are never followed (SEC-2)
 * @param submodulesSkipped submodule (gitlink) entries; submodules are never fetched (SEC-3)
 * @param oversizedSkipped files larger than the per-file limit, skipped without loading their content (SEC-3)
 * @param invalidPathsSkipped files whose path fails {@link SourceFile#isValidPath}: too long, or malformed by a crafted
 *     repository
 */
public record SnapshotStats(
        int files, int symlinksSkipped, int submodulesSkipped, int oversizedSkipped, int invalidPathsSkipped) {

    /** @throws IllegalArgumentException if a count is negative */
    public SnapshotStats {
        if (files < 0
                || symlinksSkipped < 0
                || submodulesSkipped < 0
                || oversizedSkipped < 0
                || invalidPathsSkipped < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
    }
}
