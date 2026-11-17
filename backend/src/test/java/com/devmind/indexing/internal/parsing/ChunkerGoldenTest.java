package com.devmind.indexing.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.indexing.ParsedFile;
import com.devmind.indexing.internal.domain.Chunk;
import com.devmind.indexing.internal.domain.SymbolChunker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden chunk boundaries (TEST-3, M2 step 6): every {@code golden/java/*.java} fixture, parsed and chunked, gives
 * exactly its {@code *.chunks.json}. Lives here because the parser is package-private.
 *
 * <p>To change the expectations, run {@value #REGENERATE}, then review the diff of every snapshot before committing.
 */
class ChunkerGoldenTest {

    private static final String REGENERATE = "./mvnw -q test -Dtest=ChunkerGoldenTest -DupdateGolden=true";
    private static final Path FIXTURES = Path.of("src/test/resources/golden/java"); // Surefire runs in backend/

    private static final JsonMapper JSON =
            JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final JavaParserCodeParser parser = new JavaParserCodeParser();

    /** The snapshot's shape: everything but the search text, which only adds the split identifiers. */
    record ChunkView(
            int startLine,
            int endLine,
            @Nullable Integer symbolIndex,
            int tokenEstimate,
            String header,
            String content) {

        static ChunkView of(Chunk chunk) {
            return new ChunkView(
                    chunk.startLine(),
                    chunk.endLine(),
                    chunk.symbolIndex(),
                    chunk.tokenEstimate(),
                    chunk.header(),
                    chunk.content());
        }
    }

    @TestFactory
    Stream<DynamicTest> everyFixtureChunksToItsSnapshot() throws IOException {
        List<Path> fixtures;
        try (Stream<Path> files = Files.list(FIXTURES)) {
            fixtures = files.filter(file -> file.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
        return fixtures.stream()
                .map(fixture -> DynamicTest.dynamicTest(fixture.getFileName().toString(), () -> {
                    // Given
                    String source = Files.readString(fixture).replace("\r\n", "\n");
                    String path = "golden/" + fixture.getFileName();
                    ParsedFile parsed = parser.parse(path, source);

                    // When
                    List<ChunkView> chunks = SymbolChunker.chunk(path, source, parsed).stream()
                            .map(ChunkView::of)
                            .toList();

                    // Then
                    String actual =
                            JSON.writeValueAsString(chunks).replace("\r\n", "\n") + "\n"; // LF everywhere (GEN-8)
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
                                    "%s no longer chunks to %s. If the change is intended, run %s and review the diff",
                                    fixture.getFileName(), snapshot.getFileName(), REGENERATE)
                            .isEqualTo(Files.readString(snapshot).replace("\r\n", "\n"));
                }));
    }

    private static Path snapshotOf(Path fixture) {
        String name = fixture.getFileName().toString();
        return fixture.resolveSibling(name.substring(0, name.length() - ".java".length()) + ".chunks.json");
    }
}
