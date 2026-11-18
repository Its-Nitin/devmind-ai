package com.devmind.ingestion.internal.git;

import static com.devmind.ingestion.internal.git.SnapshotAssertions.LIMITS;
import static com.devmind.ingestion.internal.git.SnapshotAssertions.assertRejected;
import static com.devmind.ingestion.internal.git.SnapshotAssertions.contents;
import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.entry;

import com.devmind.ingestion.SnapshotStats;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.SnapshotLimits;
import com.devmind.ingestion.internal.domain.SourceRejection;
import com.devmind.support.GitFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Local sources through {@link GitSources}: what a snapshot contains, and SEC-2 confinement (threats T1, T14). */
class LocalSourceTest {

    @TempDir
    Path temp;

    private Path root;
    private Path outside;
    private GitSources sources;
    private final List<GitFixture> fixtures = new ArrayList<>();

    @BeforeEach
    void createRootAndOutside() throws IOException {
        root = Files.createDirectories(temp.resolve("root"));
        outside = Files.createDirectories(temp.resolve("outside"));
        sources = sources(LIMITS);
    }

    @AfterEach
    void closeFixtures() {
        fixtures.forEach(GitFixture::close); // Windows can't delete @TempDir while pack files are open
    }

    @Nested
    class Contents {

        @Test
        void readsTheFilesCommittedAtTheBranchTipInPathOrder() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("README.md", "v1"));
            RevCommit tip = repo.commit(
                    "main",
                    tree().file("src/main/java/App.java", "class App {}")
                            .file("README.md", "v2")
                            .executable("mvnw", "#!/bin/sh"));

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then
                assertThat(snapshot.commitSha()).isEqualTo(tip.name());
                assertThat(contents(snapshot))
                        .containsExactly(
                                entry("README.md", "v2"),
                                entry("mvnw", "#!/bin/sh"),
                                entry("src/main/java/App.java", "class App {}"));
                assertThat(snapshot.stats()).isEqualTo(new SnapshotStats(3, 0, 0, 0, 0));
            }
        }

        @Test
        void resolvesLightweightAndAnnotatedTagsAndFullOrAbbreviatedShas() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            RevCommit first = repo.commit("main", tree().file("a.txt", "1"));
            RevCommit second = repo.commit("main", tree().file("a.txt", "2"));
            repo.lightweightTag("v1", first);
            repo.annotatedTag("v2", second);

            // When / Then
            assertThat(shaOf("v1")).isEqualTo(first.name());
            assertThat(shaOf("v2")).isEqualTo(second.name()); // peeled to the commit
            assertThat(shaOf(first.name())).isEqualTo(first.name());
            assertThat(shaOf(first.name().substring(0, 7))).isEqualTo(first.name());
        }

        @Test
        void prefersABranchOverATagWithTheSameName() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            RevCommit tagged = repo.commit("main", tree().file("a.txt", "1"));
            RevCommit branchTip = repo.commit("release", tree().file("a.txt", "2"));
            repo.lightweightTag("release", tagged);

            // When / Then
            assertThat(shaOf("release")).isEqualTo(branchTip.name());
        }

        @Test
        void reportsRefsThatNameNoCommit() {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            RevCommit commit = repo.commit("main", tree().file("a.txt", "1"));
            repo.lightweightTag("tree-tag", commit.getTree()); // a tag of a tree, not a commit

            // When / Then
            assertRejected(() -> open(root.resolve("app"), "develop"), SourceProblem.REF_NOT_FOUND);
            assertRejected(() -> open(root.resolve("app"), "deadbeef"), SourceProblem.REF_NOT_FOUND);
            assertRejected(() -> open(root.resolve("app"), "tree-tag"), SourceProblem.REF_NOT_FOUND);
        }

        @Test
        void readsCommittedContentNotTheWorkingTree() throws Exception {
            // Given: the working tree has an uncommitted edit and an untracked file
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("Owner.java", "committed"));
            Files.writeString(root.resolve("app/Owner.java"), "uncommitted edit");
            Files.writeString(root.resolve("app/Untracked.java"), "untracked");

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then
                assertThat(contents(snapshot)).containsExactly(entry("Owner.java", "committed"));
            }
        }

        @Test
        void readsABareRepository() throws SourceRejection {
            // Given
            GitFixture repo = bareRepo(root.resolve("app.git"));
            RevCommit tip = repo.commit("main", tree().file("a.txt", "1"));

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app.git"), "main")) {

                // Then
                assertThat(snapshot.commitSha()).isEqualTo(tip.name());
            }
        }

        @Test
        void skipsSymlinkAndSubmoduleEntriesAndCountsThem() throws SourceRejection {
            // Given: the symlink points out of the repository; its target must never be read
            GitFixture repo = repo(root.resolve("app"));
            repo.commit(
                    "main",
                    tree().file("a.txt", "kept")
                            .symlink("escape", "../../outside/secret.txt")
                            .submodule("lib/vendored", "0123456789abcdef0123456789abcdef01234567"));

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then
                assertThat(contents(snapshot)).containsOnlyKeys("a.txt");
                assertThat(snapshot.stats()).isEqualTo(new SnapshotStats(1, 1, 1, 0, 0));
            }
        }

        @Test
        void skipsFilesOverTheSizeLimitAndCountsThem() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("small.txt", "0123456789").file("large.txt", "0123456789A"));
            GitSources tenBytes = sources(new SnapshotLimits(1_000, 10, LIMITS.maxCloneBytes(), LIMITS.cloneTimeout()));

            // When
            try (SourceSnapshot snapshot = tenBytes.openLocal(location(root.resolve("app")), new RefName("main"))) {

                // Then
                assertThat(contents(snapshot)).containsOnlyKeys("small.txt");
                assertThat(snapshot.stats().oversizedSkipped()).isEqualTo(1);
            }
        }

        @Test
        void skipsPathsLongerThanTheStoredLimitAndCountsThem() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            String longPath = "dir/" + "x".repeat(997); // 1001 characters
            repo.commit("main", tree().file("a.txt", "kept").file(longPath, "dropped"));

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then
                assertThat(contents(snapshot)).containsOnlyKeys("a.txt");
                assertThat(snapshot.stats().invalidPathsSkipped()).isEqualTo(1);
            }
        }

        @Test
        void skipsDotSegmentPathsFromACraftedTreeAndCountsThem() throws SourceRejection {
            // Given: a tree Git itself would never write, with the path "src/.."
            GitFixture repo = repo(root.resolve("app"));
            repo.commitCraftedTree("main", "src", "..", "escapes the tree");

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then
                assertThat(contents(snapshot)).isEmpty();
                assertThat(snapshot.stats().invalidPathsSkipped()).isEqualTo(1);
            }
        }

        @Test
        void rejectsCommitsWithMoreEntriesThanTheFileLimit() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("a", "1").file("b", "2"));
            repo.commit("big", tree().file("a", "1").file("b", "2").symlink("c", "a"));
            GitSources twoFiles = sources(
                    new SnapshotLimits(2, LIMITS.maxFileBytes(), LIMITS.maxCloneBytes(), LIMITS.cloneTimeout()));
            String location = location(root.resolve("app"));

            // When / Then: skipped entries count toward the limit too
            try (SourceSnapshot atLimit = twoFiles.openLocal(location, new RefName("main"))) {
                assertThat(atLimit.stats().files()).isEqualTo(2);
            }
            assertRejected(() -> twoFiles.openLocal(location, new RefName("big")), SourceProblem.TOO_MANY_FILES);
        }

        @Test
        void neverRunsTheRepositorysFiltersOrHooks() throws SourceRejection {
            // Given: a smudge filter, an fsmonitor and hooks that would leave a marker file if anything ran them (T14)
            Path marker = temp.resolve("something-ran");
            String command = "sh -c 'touch " + marker.toString().replace('\\', '/') + "'";
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file(".gitattributes", "* filter=evil\n").file("secret.txt", "raw\n"));
            configure(repo, command);
            for (String hook : List.of("post-checkout", "post-merge", "reference-transaction")) {
                writeHook(repo, hook, command);
            }

            // When
            try (SourceSnapshot snapshot = open(root.resolve("app"), "main")) {

                // Then: blobs come back exactly as committed, nothing ran, nothing was checked out
                assertThat(contents(snapshot)).containsEntry("secret.txt", "raw\n");
            }
            assertThat(marker).doesNotExist();
            assertThat(root.resolve("app/secret.txt")).doesNotExist();
        }

        @Test
        void resolvesRefsWithoutOpeningASnapshot() throws SourceRejection {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            RevCommit first = repo.commit("main", tree().file("a.txt", "1"));
            RevCommit second = repo.commit("main", tree().file("a.txt", "2"));
            repo.annotatedTag("v1", first);
            String location = location(root.resolve("app"));

            // When / Then
            assertThat(sources.resolveLocal(location, new RefName("main"))).isEqualTo(second.name());
            assertThat(sources.resolveLocal(location, new RefName("v1"))).isEqualTo(first.name()); // peeled
            assertThat(sources.resolveLocal(location, new RefName(first.name().substring(0, 7))))
                    .isEqualTo(first.name());
            assertRejected(() -> sources.resolveLocal(location, new RefName("develop")), SourceProblem.REF_NOT_FOUND);
            assertRejected(
                    () -> sources.resolveLocal(location(outside), new RefName("main")),
                    SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void closedSnapshotRefusesToStreamAndClosesOnlyOnce() throws SourceRejection {
            // Given
            repo(root.resolve("app")).commit("main", tree().file("a.txt", "1"));
            SourceSnapshot snapshot = open(root.resolve("app"), "main");

            // When
            snapshot.close();
            snapshot.close();

            // Then
            assertThatIllegalStateException().isThrownBy(snapshot::files);
        }
    }

    @Nested
    class Confinement {

        @Test
        void rejectsRepositoriesOutsideTheAllowedRoots() {
            // Given
            repo(outside.resolve("app")).commit("main", tree().file("a.txt", "1"));

            // When / Then
            assertRejected(() -> open(outside.resolve("app"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void rejectsTraversalOutOfTheRoot() {
            // Given
            repo(outside.resolve("app")).commit("main", tree().file("a.txt", "1"));
            String traversal = root + "/../outside/app";

            // When / Then
            assertRejected(() -> openAt(traversal, "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void rejectsRelativeAndMalformedPaths() {
            assertRejected(() -> openAt("root/app", "main"), SourceProblem.LOCATION_NOT_ALLOWED);
            assertRejected(() -> openAt("app", "main"), SourceProblem.LOCATION_NOT_ALLOWED);
            assertRejected(() -> openAt(root + "/app\u0000", "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void doesNotRevealWhetherAPathOutsideTheRootsExists() {
            // When / Then: missing and existing outside paths look the same
            assertRejected(() -> open(outside.resolve("missing"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void reportsMissingDirectoriesAndNonRepositoriesInsideTheRoot() throws IOException {
            // Given
            Files.createDirectories(root.resolve("plain"));
            Files.writeString(root.resolve("file.txt"), "not a directory");

            // When / Then
            assertRejected(() -> open(root.resolve("missing"), "main"), SourceProblem.LOCATION_NOT_FOUND);
            assertRejected(() -> open(root.resolve("plain"), "main"), SourceProblem.NOT_A_REPOSITORY);
            assertRejected(() -> open(root.resolve("file.txt"), "main"), SourceProblem.NOT_A_REPOSITORY);
        }

        @Test
        void rejectsAGitfileThatPointsOutsideTheRoot() throws IOException {
            // Given: root/app/.git is a gitfile naming a repository outside the root
            GitFixture elsewhere = repo(outside.resolve("real"));
            elsewhere.commit("main", tree().file("a.txt", "1"));
            writeGitfile(root.resolve("app"), elsewhere.gitDirectory());

            // When / Then
            assertRejected(() -> open(root.resolve("app"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void followsAGitfileThatStaysInsideTheRoot() throws Exception {
            // Given
            GitFixture store = repo(root.resolve("store"));
            RevCommit tip = store.commit("main", tree().file("a.txt", "1"));
            writeGitfile(root.resolve("worktree"), store.gitDirectory());

            // When
            try (SourceSnapshot snapshot = open(root.resolve("worktree"), "main")) {

                // Then
                assertThat(snapshot.commitSha()).isEqualTo(tip.name());
            }
        }

        @Test
        void rejectsAlternatesThatBorrowObjectsFromOutsideTheRoot() throws IOException {
            // Given
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("a.txt", "1"));
            Path info = Files.createDirectories(repo.gitDirectory().resolve("objects/info"));
            Files.writeString(info.resolve("alternates"), outside.resolve("objects") + "\n");

            // When / Then
            assertRejected(() -> open(root.resolve("app"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void rejectsALocationThatIsASymlinkOutOfTheRoot() {
            // Given: root/link -> outside/app
            repo(outside.resolve("app")).commit("main", tree().file("a.txt", "1"));
            symlinkOrSkip(root.resolve("link"), outside.resolve("app"));

            // When / Then
            assertRejected(() -> open(root.resolve("link"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void rejectsSymlinksInsideTheGitDirectory() {
            // Given: a symlink in .git could make JGit read a pack or ref from outside the root
            GitFixture repo = repo(root.resolve("app"));
            repo.commit("main", tree().file("a.txt", "1"));
            symlinkOrSkip(repo.gitDirectory().resolve("objects/pack/escape.pack"), outside.resolve("escape.pack"));

            // When / Then
            assertRejected(() -> open(root.resolve("app"), "main"), SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void rejectsEveryLocalSourceWhenNoRootIsConfigured() {
            // Given
            repo(root.resolve("app")).commit("main", tree().file("a.txt", "1"));
            GitSources noRoots = new GitSources(List.of(), temp.resolve("clones"), LIMITS);

            // When / Then
            assertRejected(
                    () -> noRoots.openLocal(location(root.resolve("app")), new RefName("main")),
                    SourceProblem.LOCATION_NOT_ALLOWED);
        }

        @Test
        void refusesToStartWithARelativeOrMissingRoot() {
            assertThatIllegalStateException()
                    .isThrownBy(() -> new GitSources(List.of(Path.of("relative")), temp.resolve("clones"), LIMITS));
            assertThatIllegalStateException()
                    .isThrownBy(() -> new GitSources(List.of(temp.resolve("missing")), temp.resolve("clones"), LIMITS));
        }
    }

    private GitSources sources(SnapshotLimits limits) {
        return new GitSources(List.of(root), temp.resolve("clones"), limits);
    }

    private GitFixture repo(Path workTree) {
        GitFixture fixture = GitFixture.init(workTree);
        fixtures.add(fixture);
        return fixture;
    }

    private GitFixture bareRepo(Path gitDirectory) {
        GitFixture fixture = GitFixture.initBare(gitDirectory);
        fixtures.add(fixture);
        return fixture;
    }

    private SourceSnapshot open(Path directory, String ref) throws SourceRejection {
        return openAt(location(directory), ref);
    }

    private SourceSnapshot openAt(String location, String ref) throws SourceRejection {
        return sources.openLocal(location, new RefName(ref));
    }

    private String shaOf(String ref) throws SourceRejection {
        try (SourceSnapshot snapshot = open(root.resolve("app"), ref)) {
            return snapshot.commitSha();
        }
    }

    private static String location(Path directory) {
        return directory.toString();
    }

    private static void configure(GitFixture repo, String command) {
        StoredConfig config = repo.repository().getConfig();
        config.setString("filter", "evil", "smudge", command);
        config.setString("filter", "evil", "clean", command);
        config.setBoolean("filter", "evil", "required", true);
        config.setString("core", null, "fsmonitor", command);
        try {
            config.save();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeHook(GitFixture repo, String hook, String command) {
        try {
            Path hooks = Files.createDirectories(repo.gitDirectory().resolve("hooks"));
            Files.writeString(hooks.resolve(hook), "#!/bin/sh\n" + command + "\n");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void writeGitfile(Path workTree, Path gitDirectory) throws IOException {
        Files.createDirectories(workTree);
        Files.writeString(workTree.resolve(".git"), "gitdir: " + gitDirectory.toAbsolutePath() + "\n");
    }

    /** Symlinks need a privilege on Windows that dev accounts often lack; CI (Linux) always runs these tests. */
    private static void symlinkOrSkip(Path link, Path target) {
        try {
            Files.createDirectories(link.getParent());
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException ex) {
            Assumptions.abort("this account can't create symlinks: " + ex.getMessage());
        }
    }
}
