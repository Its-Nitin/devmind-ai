package com.devmind.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefNameTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "main",
                "feature/login-form",
                "release_2026.11",
                "v1.2.3",
                "abc1234",
                "0123456789abcdef0123456789abcdef01234567"
            })
    void acceptsPlainBranchTagAndShaNames(String ref) {
        // When / Then
        assertThat(RefName.parse(ref))
                .hasValueSatisfying(parsed -> assertThat(parsed.value()).isEqualTo(ref));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", // empty
                "-main", // looks like an option
                ".hidden", // dot-leading
                "feature/.hidden",
                "a..b", // Git range syntax
                "a//b", // empty segment
                "main/", // trailing slash
                "/main", // leading slash
                "main.lock",
                "HEAD@{1}", // reflog syntax
                "main~1", // ancestry syntax
                "main^",
                ":/fix", // commit message search
                "main branch", // whitespace
                "main\n",
                "mäin" // non-ASCII
            })
    void rejectsRevisionExpressionsAndMalformedNames(String ref) {
        // When / Then
        assertThat(RefName.parse(ref)).isEmpty();
    }

    @Test
    void rejectsNamesLongerThanTheLimit() {
        // Given
        String longest = "a".repeat(RefName.MAX_LENGTH);

        // When / Then
        assertThat(RefName.parse(longest)).isPresent();
        assertThat(RefName.parse(longest + "a")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc1234", "0123456789abcdef0123456789abcdef01234567"})
    void treatsSevenToFortyLowercaseHexCharactersAsAPossibleSha(String ref) {
        assertThat(new RefName(ref).mayBeSha()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc123", "ABC1234", "main", "0123456789abcdef0123456789abcdef012345678"})
    void doesNotTreatOtherNamesAsASha(String ref) {
        assertThat(new RefName(ref).mayBeSha()).isFalse();
    }

    @Test
    void constructorRejectsInvalidNames() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RefName("main~1"));
    }
}
