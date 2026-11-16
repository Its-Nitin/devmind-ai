package com.devmind.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TextNormalizerTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    @Test
    void decodesValidUtf8WithoutFallback() {
        // When
        TextNormalizer.Text text = TextNormalizer.normalize("Grüße, 世界\n".getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(text).isEqualTo(new TextNormalizer.Text("Grüße, 世界\n", false));
    }

    @Test
    void fallsBackToIso88591ForInvalidUtf8AndFlagsIt() {
        // Given: "café" in ISO-8859-1; 0xE9 alone isn't valid UTF-8
        byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);

        // When
        TextNormalizer.Text text = TextNormalizer.normalize(latin1);

        // Then
        assertThat(text).isEqualTo(new TextNormalizer.Text("café", true));
    }

    @Test
    void stripsAUtf8ByteOrderMark() {
        // Given
        byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\n'};

        // When / Then
        assertThat(TextNormalizer.normalize(withBom).text()).isEqualTo("a\n");
    }

    @Test
    void keepsAByteOrderMarkCharacterThatIsNotLeading() {
        assertThat(TextNormalizer.normalize(("a" + BOM + "b").getBytes(StandardCharsets.UTF_8))
                        .text())
                .isEqualTo("a" + BOM + "b");
    }

    @Test
    void turnsCrlfAndLoneCrIntoLf() {
        assertThat(TextNormalizer.normalize("a\r\nb\rc\n\r\n".getBytes(StandardCharsets.UTF_8))
                        .text())
                .isEqualTo("a\nb\nc\n\n");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {"''|0", "a|1", "a\\n|1", "a\\nb|2", "a\\nb\\n|2", "\\n|1", "\\n\\n|2"})
    void countsLinesLikeAnEditor(String escaped, int expected) {
        assertThat(TextNormalizer.lineCount(escaped.replace("\\n", "\n"))).isEqualTo(expected);
    }
}
