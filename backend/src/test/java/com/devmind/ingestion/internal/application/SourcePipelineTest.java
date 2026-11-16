package com.devmind.ingestion.internal.application;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ingestion.ExclusionReason;
import com.devmind.ingestion.FileOutcome;
import com.devmind.ingestion.IngestionStats;
import com.devmind.ingestion.NormalizedFile;
import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.SnapshotOutcome;
import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.ContentHash;
import com.devmind.ingestion.internal.domain.SecretRedactor;
import com.devmind.project.SourceType;
import com.devmind.support.GitFixture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.unit.DataSize;

/** M2 step 4: each row of the table, end to end through {@link SourcePipeline}. */
class SourcePipelineTest {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private final SourcePipeline pipeline = new SourcePipeline();

    @ParameterizedTest
    @CsvSource({
        "target/classes/application.yml,  PATH",
        "web/node_modules/lib/index.ts,   PATH",
        ".env,                            SECRET_FILE",
        "deploy/server.pem,               SECRET_FILE",
        "src/main/resources/logo.png,     EXTENSION",
        "Dockerfile,                      EXTENSION"
    })
    void excludesByPathBeforeLookingAtContent(String path, ExclusionReason reason) {
        assertThat(pipeline.process(file(path, "text"))).isEqualTo(new FileOutcome.Excluded(reason));
    }

    @Test
    void checksSecretFilePatternsBeforeTheExtensionAllowList() {
        // Given: a secret file that also has an allowed extension would otherwise be kept
        SourceFile credentials = file("config/credentials.json", "{\"key\": \"value\"}");

        // When / Then
        assertThat(pipeline.process(credentials)).isEqualTo(new FileOutcome.Excluded(ExclusionReason.SECRET_FILE));
    }

    @Test
    void excludesBinaryContentEvenWithAnAllowedExtension() {
        // Given
        SourceFile binary = new SourceFile("data/dump.json", new byte[] {'{', 0, '}'});

        // When / Then
        assertThat(pipeline.process(binary)).isEqualTo(new FileOutcome.Excluded(ExclusionReason.BINARY));
    }

    @Test
    void keepsAJavaFileNormalizedAndHashed() {
        // When
        NormalizedFile kept = kept(pipeline.process(file("src/main/java/App.java", "class App {}\n")));

        // Then
        assertThat(kept)
                .isEqualTo(new NormalizedFile(
                        "src/main/java/App.java",
                        "java",
                        "class App {}\n",
                        ContentHash.sha256("class App {}\n"),
                        13,
                        1,
                        0,
                        false));
    }

    @Test
    void crlfAndLfVersionsOfTheSameTextGetTheSameHash() {
        // Given
        SourceFile lf = file("a/App.java", "class App {\n}\n");
        SourceFile crlfWithBom =
                new SourceFile("b/App.java", (BOM + "class App {\r\n}\r\n").getBytes(StandardCharsets.UTF_8));

        // When
        NormalizedFile fromLf = kept(pipeline.process(lf));
        NormalizedFile fromCrlf = kept(pipeline.process(crlfWithBom));

        // Then
        assertThat(fromCrlf.contentHash()).isEqualTo(fromLf.contentHash());
        assertThat(fromCrlf.content()).isEqualTo(fromLf.content());
    }

    @Test
    void redactsBeforeHashingAndKeepsTheLineCount() {
        // Given
        String original = "spring.datasource.url=jdbc:postgresql://db/app\nspring.datasource.password=hunter2hunter2\n";

        // When
        NormalizedFile kept = kept(pipeline.process(file("src/main/resources/application.properties", original)));

        // Then: the stored content and its hash never contain the secret
        assertThat(kept.content()).doesNotContain("hunter2").contains(SecretRedactor.MARKER);
        assertThat(kept.contentHash()).isEqualTo(ContentHash.sha256(kept.content()));
        assertThat(kept.lineCount()).isEqualTo(2);
        assertThat(kept.redactionCount()).isEqualTo(1);
    }

    @Test
    void flagsTheEncodingFallbackForNonUtf8Text() {
        // Given
        SourceFile latin1 = new SourceFile("README.md", "café".getBytes(StandardCharsets.ISO_8859_1));

        // When
        NormalizedFile kept = kept(pipeline.process(latin1));

        // Then
        assertThat(kept.content()).isEqualTo("café");
        assertThat(kept.encodingFallback()).isTrue();
    }

    @Test
    void statsOfARealCommitAddUpToEveryTrackedEntry(@TempDir Path temp) throws IOException {
        // Given: one entry for every way of being kept or excluded
        Path root = Files.createDirectories(temp.resolve("root"));
        try (GitFixture repo = GitFixture.init(root.resolve("app"))) {
            repo.commit(
                    "main",
                    tree().file("pom.xml", "<project/>")
                            .file("src/main/java/App.java", "class App {}")
                            .file("src/main/resources/application.properties", "db.password=hunter2hunter2")
                            .file("target/App.class", "compiled")
                            .file(".env", "TOKEN=x")
                            .file("logo.png", "png")
                            .file("data.json", new byte[] {0, 1, 2})
                            .file("big.md", "x".repeat(2048))
                            .symlink("link", "pom.xml")
                            .submodule("lib", "0123456789abcdef0123456789abcdef01234567"));
        }
        SourceSnapshotService snapshots = new SourceSnapshotService(new IngestionProperties(
                List.of(root),
                List.of(),
                temp.resolve("clones"),
                Duration.ofMinutes(1),
                DataSize.ofMegabytes(1),
                100,
                DataSize.ofKilobytes(1)));

        // When
        IngestionStats stats;
        SnapshotOutcome outcome = snapshots.open(
                new ProjectSource(SourceType.LOCAL, root.resolve("app").toString()), "main");
        try (SourceSnapshot snapshot = ((SnapshotOutcome.Opened) outcome).snapshot()) {
            stats = snapshot.files()
                    .map(pipeline::process)
                    .reduce(IngestionStats.of(snapshot.stats()), IngestionStats::add, (a, b) -> {
                        throw new UnsupportedOperationException("sequential");
                    });
        }

        // Then: kept + excluded = total, one per reason
        assertThat(stats.kept()).isEqualTo(3);
        assertThat(stats.redactions()).isEqualTo(1);
        assertThat(stats.excluded())
                .containsEntry(ExclusionReason.PATH, 1)
                .containsEntry(ExclusionReason.SECRET_FILE, 1)
                .containsEntry(ExclusionReason.SIZE, 1)
                .containsEntry(ExclusionReason.BINARY, 1)
                .containsEntry(ExclusionReason.EXTENSION, 1)
                .containsEntry(ExclusionReason.SYMLINK, 1)
                .containsEntry(ExclusionReason.SUBMODULE, 1)
                .containsEntry(ExclusionReason.INVALID_PATH, 0);
        assertThat(stats.total()).isEqualTo(10);
    }

    @Test
    void rulesVersionIsStable() {
        assertThat(pipeline.rulesVersion()).isEqualTo("filters-1");
    }

    private static SourceFile file(String path, String content) {
        return new SourceFile(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static NormalizedFile kept(FileOutcome outcome) {
        assertThat(outcome).isInstanceOf(FileOutcome.Kept.class);
        return ((FileOutcome.Kept) outcome).file();
    }
}
