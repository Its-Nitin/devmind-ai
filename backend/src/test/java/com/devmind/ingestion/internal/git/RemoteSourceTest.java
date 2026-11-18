package com.devmind.ingestion.internal.git;

import static com.devmind.ingestion.internal.git.SnapshotAssertions.LIMITS;
import static com.devmind.ingestion.internal.git.SnapshotAssertions.assertRejected;
import static com.devmind.ingestion.internal.git.SnapshotAssertions.contents;
import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import com.devmind.support.GitFixture;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Remote sources (SEC-3, threats T2/T3), cloned from a local bare repository over {@code file://}: the cloner trusts
 * its caller to have validated the URL, so no network is needed (TEST-5). URL validation is {@code
 * RemoteUrlPolicyTest}'s job.
 */
class RemoteSourceTest {

    @TempDir
    Path temp;

    private GitFixture origin;
    private RevCommit first;
    private RevCommit second;
    private Path clones;

    @BeforeAll
    static void isolateJGit() {
        JGitIsolation.install(); // as GitSources does in production
    }

    @BeforeEach
    void createOrigin() {
        origin = GitFixture.initBare(temp.resolve("origin.git"));
        first = origin.commit("main", tree().file("a.txt", "1"));
        second = origin.commit("main", tree().file("a.txt", "2").file("b.txt", "3"));
        origin.annotatedTag("v1", first);
        clones = temp.resolve("clones");
    }

    @AfterEach
    void closeOrigin() {
        origin.close();
    }

    @Test
    void clonesABranchTipShallowlyIntoABareRepositoryWithoutRedirectsOrAutoGc() throws Exception {
        // When
        try (ClonedRepository clone = cloner(LIMITS).clone(originUri(), new RefName("main"))) {

            // Then: only the tip commit arrived
            Repository repository = clone.repository();
            assertThat(repository.isBare()).isTrue();
            assertThat(repository.resolve("refs/heads/main")).isEqualTo(second);
            assertThat(repository.getObjectDatabase().getShallowCommits()).containsExactly(second);
            assertThat(repository.getObjectDatabase().has(first)).isFalse();
            assertThat(repository.getConfig().getString("http", null, "followRedirects"))
                    .isEqualTo("false");
            assertThat(repository.getConfig().getInt("gc", null, "auto", -1)).isZero();
        }
    }

    @Test
    void readsTheCommitOfAnAnnotatedTag() throws SourceRejection {
        // When
        try (SourceSnapshot snapshot = sources(LIMITS).openRemote(URI.create(originUri()), new RefName("v1"))) {

            // Then
            assertThat(snapshot.commitSha()).isEqualTo(first.name());
            assertThat(contents(snapshot)).containsExactly(entry("a.txt", "1"));
        }
    }

    @Test
    void readsAnOlderCommitBySha() throws SourceRejection {
        // When / Then: full and abbreviated
        for (String sha : List.of(first.name(), first.name().substring(0, 7))) {
            try (SourceSnapshot snapshot = sources(LIMITS).openRemote(URI.create(originUri()), new RefName(sha))) {
                assertThat(snapshot.commitSha()).isEqualTo(first.name());
            }
        }
    }

    @Test
    void deletesTheCloneWhenTheSnapshotCloses() throws Exception {
        // Given
        SourceSnapshot snapshot = sources(LIMITS).openRemote(URI.create(originUri()), new RefName("main"));
        assertThat(cloneDirectories()).hasSize(1);

        // When
        snapshot.close();

        // Then
        assertThat(cloneDirectories()).isEmpty();
    }

    @Test
    void rejectsAMissingRefWithoutLeavingACloneBehind() throws IOException {
        // When / Then
        assertRejected(() -> cloner(LIMITS).clone(originUri(), new RefName("develop")), SourceProblem.REF_NOT_FOUND);
        assertThat(cloneDirectories()).isEmpty();
    }

    @Test
    void reportsAMissingRepository() throws IOException {
        // Given
        String missing = temp.resolve("missing.git").toUri().toString();

        // When / Then
        assertRejected(() -> cloner(LIMITS).clone(missing, new RefName("main")), SourceProblem.LOCATION_NOT_FOUND);
        assertThat(cloneDirectories()).isEmpty();
    }

    @Test
    void stopsAtTheCloneSizeLimit() throws IOException {
        // Given
        SnapshotLimits oneByte = new SnapshotLimits(LIMITS.maxFiles(), LIMITS.maxFileBytes(), 1, LIMITS.cloneTimeout());

        // When / Then
        assertRejected(() -> cloner(oneByte).clone(originUri(), new RefName("main")), SourceProblem.TOO_LARGE);
        assertThat(cloneDirectories()).isEmpty();
    }

    @Test
    void resolvesRefsWithOneLsRemoteAndLeavesNothingBehind() throws Exception {
        // Given
        RemoteCloner cloner = cloner(LIMITS);

        // When / Then: a branch, an annotated tag peeled to its commit, and a SHA prefix passed through
        assertThat(cloner.resolve(originUri(), new RefName("main"))).isEqualTo(second.name());
        assertThat(cloner.resolve(originUri(), new RefName("v1"))).isEqualTo(first.name());
        assertThat(cloner.resolve(originUri(), new RefName("abcdef1"))).isEqualTo("abcdef1");
        assertRejected(() -> cloner.resolve(originUri(), new RefName("develop")), SourceProblem.REF_NOT_FOUND);
        assertRejected(
                () -> cloner.resolve(temp.resolve("missing.git").toUri().toString(), new RefName("main")),
                SourceProblem.LOCATION_NOT_FOUND);
        assertThat(cloneDirectories()).isEmpty();
    }

    @Test
    void stopsAtTheCloneTimeout() throws IOException {
        // Given: a deadline that has passed before the clone starts
        SnapshotLimits noTime = new SnapshotLimits(
                LIMITS.maxFiles(), LIMITS.maxFileBytes(), LIMITS.maxCloneBytes(), Duration.ofNanos(1));

        // When / Then
        assertRejected(() -> cloner(noTime).clone(originUri(), new RefName("main")), SourceProblem.TIMED_OUT);
        assertThat(cloneDirectories()).isEmpty();
    }

    private RemoteCloner cloner(SnapshotLimits limits) {
        return new RemoteCloner(new CloneWorkspace(clones), limits);
    }

    private GitSources sources(SnapshotLimits limits) {
        return new GitSources(List.of(), clones, limits);
    }

    private String originUri() {
        return temp.resolve("origin.git").toUri().toString();
    }

    private List<Path> cloneDirectories() throws IOException {
        try (Stream<Path> children = Files.list(clones)) {
            return children.toList();
        }
    }
}
