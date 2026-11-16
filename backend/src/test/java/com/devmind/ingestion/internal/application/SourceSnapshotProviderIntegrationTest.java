package com.devmind.ingestion.internal.application;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.SnapshotOutcome;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.SourceSnapshotProvider;
import com.devmind.project.SourceType;
import com.devmind.support.GitFixture;
import com.devmind.support.IntegrationTest;
import java.nio.file.Path;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.unit.DataSize;

/** The provider as other modules get it: wired, and configured from {@code devmind.ingestion.*}. */
@IntegrationTest
class SourceSnapshotProviderIntegrationTest {

    @TempDir
    Path temp; // under java.io.tmpdir, the test profile's allowed root

    private final SourceSnapshotProvider provider;
    private final IngestionProperties properties;

    @Autowired
    SourceSnapshotProviderIntegrationTest(SourceSnapshotProvider provider, IngestionProperties properties) {
        this.provider = provider;
        this.properties = properties;
    }

    @Test
    void bindsTheIngestionDefaults() {
        assertThat(properties.allowedHosts()).containsExactly("github.com", "gitlab.com");
        assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofKilobytes(512));
        assertThat(properties.allowedRoots())
                .singleElement()
                .satisfies(root -> assertThat(root).isAbsolute());
    }

    @Test
    void opensAFixtureRepositoryUnderTheConfiguredRoot() {
        // Given
        RevCommit tip;
        try (GitFixture repo = GitFixture.init(temp.resolve("app"))) {
            tip = repo.commit("main", tree().file("README.md", "hello"));
        }

        // When
        SnapshotOutcome outcome = provider.open(
                new ProjectSource(SourceType.LOCAL, temp.resolve("app").toString()), "main");

        // Then
        assertThat(outcome).isInstanceOfSatisfying(SnapshotOutcome.Opened.class, opened -> {
            try (SourceSnapshot snapshot = opened.snapshot()) {
                assertThat(snapshot.commitSha()).isEqualTo(tip.name());
                assertThat(snapshot.stats().files()).isEqualTo(1);
            }
        });
    }
}
