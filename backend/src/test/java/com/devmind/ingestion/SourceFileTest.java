package com.devmind.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceFileTest {

    private static final byte[] SECRET = "password=hunter2".getBytes(StandardCharsets.UTF_8);

    @Test
    void copiesContentInAndOutSoTheRecordStaysImmutable() {
        // Given
        byte[] content = SECRET.clone();
        SourceFile file = new SourceFile("a.properties", content);

        // When
        content[0] = 'X';
        file.content()[1] = 'Y';

        // Then
        assertThat(file.content()).isEqualTo(SECRET);
    }

    @Test
    void comparesByPathAndContent() {
        assertThat(new SourceFile("a", SECRET.clone()))
                .isEqualTo(new SourceFile("a", SECRET.clone()))
                .hasSameHashCodeAs(new SourceFile("a", SECRET.clone()))
                .isNotEqualTo(new SourceFile("b", SECRET.clone()))
                .isNotEqualTo(new SourceFile("a", new byte[0]));
    }

    @Test
    void neverPrintsItsContent() {
        assertThat(new SourceFile("a.properties", SECRET))
                .hasToString("SourceFile[path=a.properties, bytes=16]")
                .asString()
                .doesNotContain("hunter2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/etc/passwd", "src/", "src//Main.java", "./Main.java", "src/../../etc/passwd", ".."})
    void rejectsEmptyAbsoluteAndNonNormalizedPaths(String path) {
        assertThat(SourceFile.isValidPath(path)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceFile(path, SECRET));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pom.xml", "src/main/java/App.java", ".github/workflows/ci.yml", "a/..b/c.."})
    void acceptsRelativeNormalizedPaths(String path) {
        assertThat(SourceFile.isValidPath(path)).isTrue();
    }

    @Test
    void rejectsPathsLongerThanTheStoredLimit() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SourceFile("x".repeat(SourceFile.MAX_PATH_LENGTH + 1), SECRET));
    }
}
