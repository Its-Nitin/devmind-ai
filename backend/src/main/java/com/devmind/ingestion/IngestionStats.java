package com.devmind.ingestion;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Counts for one ingestion run: every tracked entry of the commit is either kept or excluded for exactly one reason,
 * so {@code kept + excluded = total} (ERR-4). Immutable: {@link #add} returns a new value.
 *
 * <p>Start with {@link #of(SnapshotStats)}, which carries the entries the snapshot already skipped, then {@link #add}
 * each file's outcome.
 *
 * @param kept files indexed
 * @param excluded count per reason; every reason is present, zero if unused, in declaration order
 * @param encodingFallbacks kept files decoded as ISO-8859-1 because they weren't valid UTF-8
 * @param redactions secrets replaced across kept files
 */
public record IngestionStats(int kept, Map<ExclusionReason, Integer> excluded, int encodingFallbacks, int redactions) {

    /** @throws IllegalArgumentException if a count is negative */
    public IngestionStats {
        EnumMap<ExclusionReason, Integer> counts = new EnumMap<>(ExclusionReason.class);
        for (ExclusionReason reason : ExclusionReason.values()) {
            int count = excluded.getOrDefault(reason, 0);
            if (count < 0) {
                throw new IllegalArgumentException("counts must not be negative");
            }
            counts.put(reason, count);
        }
        if (kept < 0 || encodingFallbacks < 0 || redactions < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        excluded = Collections.unmodifiableMap(counts);
    }

    /** Returns stats holding what {@code snapshot} skipped, with nothing kept yet. */
    public static IngestionStats of(SnapshotStats snapshot) {
        return new IngestionStats(
                0,
                Map.of(
                        ExclusionReason.SIZE, snapshot.oversizedSkipped(),
                        ExclusionReason.SYMLINK, snapshot.symlinksSkipped(),
                        ExclusionReason.SUBMODULE, snapshot.submodulesSkipped(),
                        ExclusionReason.INVALID_PATH, snapshot.invalidPathsSkipped()),
                0,
                0);
    }

    /** Returns these stats with {@code outcome} counted. */
    public IngestionStats add(FileOutcome outcome) {
        return switch (outcome) {
            case FileOutcome.Kept(NormalizedFile file) ->
                new IngestionStats(
                        kept + 1,
                        excluded,
                        encodingFallbacks + (file.encodingFallback() ? 1 : 0),
                        redactions + file.redactionCount());
            case FileOutcome.Excluded(ExclusionReason reason) -> {
                EnumMap<ExclusionReason, Integer> counts = new EnumMap<>(excluded);
                counts.merge(reason, 1, Integer::sum);
                yield new IngestionStats(kept, counts, encodingFallbacks, redactions);
            }
        };
    }

    /** Returns how many entries were excluded for {@code reason}. */
    public int excluded(ExclusionReason reason) {
        return excluded.getOrDefault(reason, 0);
    }

    /** Returns every tracked entry counted: kept plus all exclusions. */
    public int total() {
        return kept + excluded.values().stream().mapToInt(Integer::intValue).sum();
    }
}
