package com.devmind.indexing.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.indexing.ParsedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden tests (TEST-3): every {@code golden/java/*.java} fixture parses to exactly its {@code *.symbols.json}.
 *
 * <p>To change the expectations, run {@value #REGENERATE}, then review the diff of every snapshot before committing:
 * an update is a reviewed act, not a way to make the test pass.
 */
class JavaParserGoldenTest {

    private static final String REGENERATE = "./mvnw -q test -Dtest=JavaParserGoldenTest -DupdateGolden=true";
    private static final Path FIXTURES = Path.of("src/test/resources/golden/java"); // Surefire runs in backend/

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final JavaParserCodeParser parser = new JavaParserCodeParser();

    @TestFactory
    Stream<DynamicTest> everyFixtureParsesToItsSnapshot() throws IOException {
        List<Path> fixtures;
        try (Stream<Path> files = Files.list(FIXTURES)) {
            fixtures = files.filter(file -> file.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        assertThat(fixtures).as("golden fixtures").hasSizeGreaterThanOrEqualTo(6);
        return fixtures.stream()
                .map(fixture -> DynamicTest.dynamicTest(fixture.getFileName().toString(), () -> {
                    // Given: a path outside src/test/, so test flags come from annotations alone
                    String source = Files.readString(fixture).replace("\r\n", "\n");
                    String path = "golden/" + fixture.getFileName();

                    // When
                    ParsedFile parsed = parser.parse(path, source);

                    // Then
                    // LF everywhere (GEN-8): the pretty printer uses the platform separator, and CI runs on Linux.
                    String actual = JSON.writeValueAsString(parsed).replace("\r\n", "\n") + "\n";
                    Path snapshot = snapshotOf(fixture);
                    if (Boolean.parseBoolean(System.getProperty("updateGolden"))) {
                        Files.writeString(snapshot, actual);
                        return;
                    }
                    assertThat(snapshot)
                            .as("missing snapshot; generate it with " + REGENERATE)
                            .exists();
                    assertThat(actual)
                            .as(
                                    "%s no longer matches %s. If the change is intended, run %s and review the diff",
                                    fixture.getFileName(), snapshot.getFileName(), REGENERATE)
                            .isEqualTo(Files.readString(snapshot).replace("\r\n", "\n"));
                }));
    }

    private static Path snapshotOf(Path fixture) {
        String name = fixture.getFileName().toString();
        return fixture.resolveSibling(name.substring(0, name.length() - ".java".length()) + ".symbols.json");
    }
}
