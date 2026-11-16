package com.devmind.ingestion.internal.application;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.SnapshotOutcome;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.project.SourceType;
import com.devmind.support.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

/** The module boundary: every way of failing comes back as a {@link SnapshotOutcome.Failed}, never an exception. */
class SourceSnapshotServiceTest {

    @TempDir
    Path temp;

    private Path root;
    private GitFixture repo;
    private RevCommit tip;
    private SourceSnapshotService service;

    @BeforeEach
    void createRepositoryAndService() throws IOException {
        root = Files.createDirectories(temp.resolve("root"));
        repo = GitFixture.init(root.resolve("app"));
        tip = repo.commit("main", tree().file("a.txt", "1"));
        service = new SourceSnapshotService(new IngestionProperties(
                List.of(root),
                List.of("github.com"),
                temp.resolve("clones"),
                Duration.ofMinutes(1),
                DataSize.ofMegabytes(10),
                100,
                DataSize.ofKilobytes(512)));
    }

    @AfterEach
    void closeRepository() {
        repo.close();
    }

    @Test
    void opensALocalSourceAtTheRequestedRef() {
        // When
        SnapshotOutcome outcome = service.open(local(root.resolve("app")), "main");

        // Then
        assertThat(outcome).isInstanceOfSatisfying(SnapshotOutcome.Opened.class, opened -> {
            try (SourceSnapshot snapshot = opened.snapshot()) {
                assertThat(snapshot.commitSha()).isEqualTo(tip.name());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"main~1", "HEAD@{1}", "", "-x"})
    void rejectsRefsThatAreNotPlainNamesOrShas(String ref) {
        assertThat(service.open(local(root.resolve("app")), ref))
                .isEqualTo(new SnapshotOutcome.Failed(SourceProblem.INVALID_REF));
    }

    @Test
    void returnsALocationOutsideTheRootsAsAFailure() {
        assertThat(service.open(local(temp.resolve("elsewhere")), "main"))
                .isEqualTo(new SnapshotOutcome.Failed(SourceProblem.LOCATION_NOT_ALLOWED));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://github.com/owner/repo",
                "https://gitlab.com/owner/repo", // not on this service's allow-list
                "https://127.0.0.1/owner/repo"
            })
    void rejectsRemoteUrlsThatBreakThePolicyWithoutCloning(String url) throws IOException {
        // When
        SnapshotOutcome outcome = service.open(new ProjectSource(SourceType.GIT_PUBLIC, url), "main");

        // Then
        assertThat(outcome).isEqualTo(new SnapshotOutcome.Failed(SourceProblem.URL_NOT_ALLOWED));
        assertThat(temp.resolve("clones")).isEmptyDirectory();
    }

    @Test
    void neverReachesARemoteRepositoryThroughTheFileScheme() {
        // Given: the cloner accepts file:// (its tests use it); the service must not let it through
        String fileUrl = root.resolve("app").toUri().toString();

        // When / Then
        assertThat(service.open(new ProjectSource(SourceType.GIT_PUBLIC, fileUrl), "main"))
                .isEqualTo(new SnapshotOutcome.Failed(SourceProblem.URL_NOT_ALLOWED));
    }

    private static ProjectSource local(Path directory) {
        return new ProjectSource(SourceType.LOCAL, directory.toString());
    }
}
