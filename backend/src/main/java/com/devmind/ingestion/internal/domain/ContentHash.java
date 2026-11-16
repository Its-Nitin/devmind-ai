package com.devmind.ingestion.internal.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The blob identity of normalized, redacted text (M2 step 4, row 9; ADR-0003). */
public final class ContentHash {

    private ContentHash() {}

    /** Returns the SHA-256 of {@code text} as UTF-8, as 64 lowercase hex characters. */
    public static String sha256(String text) {
        return HexFormat.of().formatHex(digest().digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java platform must support SHA-256", ex);
        }
    }
}
