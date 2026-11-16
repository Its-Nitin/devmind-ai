package com.devmind.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BinarySnifferAndHashTest {

    @Test
    void treatsANulByteInTheFirst8KbAsBinary() {
        // Given
        byte[] content = new byte[BinarySniffer.SNIFF_BYTES];
        Arrays.fill(content, (byte) 'a');
        content[BinarySniffer.SNIFF_BYTES - 1] = 0;

        // When / Then
        assertThat(BinarySniffer.isBinary(content)).isTrue();
    }

    @Test
    void treatsTextAndLateNulBytesAsText() {
        // Given: a NUL just past the sniffed prefix
        byte[] content = new byte[BinarySniffer.SNIFF_BYTES + 1];
        Arrays.fill(content, (byte) 'a');
        content[BinarySniffer.SNIFF_BYTES] = 0;

        // When / Then
        assertThat(BinarySniffer.isBinary(content)).isFalse();
        assertThat(BinarySniffer.isBinary("plain text".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
        assertThat(BinarySniffer.isBinary(new byte[0])).isFalse();
    }

    @Test
    void hashesUtf8TextWithSha256AsLowercaseHex() {
        assertThat(ContentHash.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ContentHash.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
