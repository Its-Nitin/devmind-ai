package com.devmind.ingestion.internal.domain;

/** Tells binary files from text the way Git does: a NUL byte near the start (M2 step 4, row 4). */
public final class BinarySniffer {

    /** How much of a file is examined. */
    public static final int SNIFF_BYTES = 8 * 1024;

    private BinarySniffer() {}

    /** Returns whether {@code content} has a NUL byte in its first {@link #SNIFF_BYTES} bytes. */
    public static boolean isBinary(byte[] content) {
        int end = Math.min(content.length, SNIFF_BYTES);
        for (int i = 0; i < end; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
