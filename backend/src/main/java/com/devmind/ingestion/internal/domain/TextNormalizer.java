package com.devmind.ingestion.internal.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Decodes committed bytes and normalizes the text (M2 step 4, rows 6-7), so the same text hashes the same whatever
 * its encoding marker or line endings.
 */
public final class TextNormalizer {

    private static final char BYTE_ORDER_MARK = 0xFEFF;

    /**
     * Decoded, normalized text.
     *
     * @param text no BOM, LF line endings only
     * @param encodingFallback whether the bytes weren't valid UTF-8 and were read as ISO-8859-1 instead
     */
    public record Text(String text, boolean encodingFallback) {}

    private TextNormalizer() {}

    /**
     * Decodes {@code content} as strict UTF-8, or as ISO-8859-1 if it isn't valid UTF-8 (every byte sequence is valid
     * ISO-8859-1, so this never fails). Then strips a leading BOM and turns CRLF and lone CR into LF.
     */
    public static Text normalize(byte[] content) {
        String decoded;
        boolean fallback;
        try {
            decoded = strictUtf8().decode(ByteBuffer.wrap(content)).toString();
            fallback = false;
        } catch (CharacterCodingException ex) {
            decoded = new String(content, StandardCharsets.ISO_8859_1);
            fallback = true;
        }
        if (!decoded.isEmpty() && decoded.charAt(0) == BYTE_ORDER_MARK) {
            decoded = decoded.substring(1);
        }
        return new Text(decoded.replace("\r\n", "\n").replace('\r', '\n'), fallback);
    }

    /** Returns the number of lines: a final line without a newline counts, and empty text has none. */
    public static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int newlines = (int) text.chars().filter(c -> c == '\n').count();
        return text.endsWith("\n") ? newlines : newlines + 1;
    }

    /** A decoder per call: decoders are stateful and not thread-safe. */
    private static CharsetDecoder strictUtf8() {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
    }
}
