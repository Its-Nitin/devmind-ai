package com.devmind.ingestion.internal.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CloneWorkspaceTest {

    @TempDir
    Path temp;

    @Test
    void sweepsClonesLeftByAnEarlierRunAndNothingElse() throws IOException {
        // Given: a crash left a clone behind, next to something that isn't a clone
        Path clones = temp.resolve("clones");
        Path stale = Files.createDirectories(clones.resolve(CloneWorkspace.PREFIX + "123/objects/pack"));
        Files.writeString(stale.resolve("pack-1.pack"), "data");
        Path unrelated = Files.createDirectories(clones.resolve("keep-me"));

        // When
        new CloneWorkspace(clones);

        // Then
        assertThat(clones.resolve(CloneWorkspace.PREFIX + "123")).doesNotExist();
        assertThat(unrelated).isDirectory();
    }

    @Test
    void createsTheDirectoryAndOneFreshDirectoryPerClone() throws IOException {
        // Given
        CloneWorkspace workspace = new CloneWorkspace(temp.resolve("not/yet/there"));

        // When
        Path a = workspace.newCloneDirectory();
        Path b = workspace.newCloneDirectory();

        // Then
        assertThat(a).isEmptyDirectory().isNotEqualTo(b);
        assertThat(a.getFileName().toString()).startsWith(CloneWorkspace.PREFIX);
        assertThat(a.getParent()).isEqualTo(temp.resolve("not/yet/there").toAbsolutePath());
    }

    @Test
    void deletingIsRecursiveAndToleratesMissingDirectories() throws IOException {
        // Given
        CloneWorkspace workspace = new CloneWorkspace(temp.resolve("clones"));
        Path clone = workspace.newCloneDirectory();
        Files.writeString(Files.createDirectories(clone.resolve("objects")).resolve("x"), "data");

        // When
        workspace.delete(clone);
        workspace.delete(clone);

        // Then
        assertThat(clone).doesNotExist();
    }
}
