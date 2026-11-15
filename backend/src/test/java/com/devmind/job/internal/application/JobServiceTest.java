package com.devmind.job.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devmind.job.JobApi;
import com.devmind.job.JobId;
import com.devmind.job.JobProgress;
import com.devmind.job.JobRef;
import com.devmind.job.JobStatus;
import com.devmind.job.JobType;
import com.devmind.job.JobView;
import com.devmind.shared.NotFoundException;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestProjects;
import com.devmind.support.TestProjects.OwnedProject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Idempotent submit (ADR-0009) and owner-only reads (SEC-1, API-8). */
@IntegrationTest
@Import(JobTestConfiguration.class)
class JobServiceTest {

    private final JobApi jobs;
    private final TestProjects projects;
    private final JobRows rows;

    @Autowired
    JobServiceTest(JobApi jobs, JdbcClient jdbc) {
        this.jobs = jobs;
        this.projects = new TestProjects(jdbc);
        this.rows = new JobRows(jdbc);
    }

    @Test
    void resubmittingTheSameKeyReturnsTheSameJob() {
        // Given
        long project = projects.insertProject().id();
        JobRef first = jobs.submit(project, JobType.INDEX, "sha1+cfg", Map.of());

        // When
        JobRef again = jobs.submit(project, JobType.INDEX, "sha1+cfg", Map.of());

        // Then
        assertThat(again).isEqualTo(first);
        assertThat(first.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void differentKeyTypeOrProjectCreatesDifferentJobs() {
        // Given
        long project = projects.insertProject().id();
        long otherProject = projects.insertProject().id();
        JobId base = jobs.submit(project, JobType.INDEX, "k", Map.of()).id();

        // When
        List<JobId> others = List.of(
                jobs.submit(project, JobType.INDEX, "other-key", Map.of()).id(),
                jobs.submit(project, JobType.ANALYSIS, "k", Map.of()).id(),
                jobs.submit(otherProject, JobType.INDEX, "k", Map.of()).id());

        // Then
        assertThat(others).doesNotContain(base).doesNotHaveDuplicates();
    }

    @Test
    void submittingAgainAfterAFailureCreatesANewJob() {
        // Given
        long project = projects.insertProject().id();
        JobId failed = jobs.submit(project, JobType.INDEX, "k", Map.of()).id();
        rows.markFailed(failed);

        // When
        JobRef retry = jobs.submit(project, JobType.INDEX, "k", Map.of());

        // Then
        assertThat(retry.id()).isNotEqualTo(failed);
        assertThat(retry.status()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void concurrentSubmitsOfTheSameKeyAgreeOnOneJob() throws InterruptedException {
        // Given
        long project = projects.insertProject().id();
        Callable<JobId> submit =
                () -> jobs.submit(project, JobType.INDEX, "race", Map.of()).id();

        // When
        List<JobId> ids;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<JobId>> futures =
                    pool.invokeAll(IntStream.range(0, 8).mapToObj(i -> submit).toList());
            ids = futures.stream().map(JobServiceTest::join).toList();
        }

        // Then
        assertThat(ids).hasSize(8).containsOnly(ids.getFirst());
    }

    @Test
    void rejectsBlankOrOverlongIdempotencyKey() {
        long project = projects.insertProject().id();
        String tooLong = "k".repeat(JobService.MAX_IDEMPOTENCY_KEY_LENGTH + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> jobs.submit(project, JobType.INDEX, " ", Map.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> jobs.submit(project, JobType.INDEX, tooLong, Map.of()));
    }

    @Test
    void ownerSeesTheJobAndEveryoneElseGetsTheSameNotFoundAsForAMissingJob() {
        // Given
        OwnedProject project = projects.insertProject();
        JobId id = jobs.submit(project.id(), JobType.INDEX, "k", Map.of()).id();

        // When
        JobView view = jobs.get(project.owner(), id);

        // Then
        assertThat(view.id()).isEqualTo(id);
        assertThat(view.type()).isEqualTo(JobType.INDEX);
        assertThat(view.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(view.progress()).isEqualTo(JobProgress.NONE);
        assertThat(view.attempts()).isZero();
        assertThat(view.errorCode()).isNull();
        assertThatThrownBy(() -> jobs.get(projects.insertUser(), id))
                .isInstanceOfSatisfying(
                        NotFoundException.class, ex -> assertThat(ex.code()).isEqualTo("job-not-found"));
        assertThatThrownBy(() -> jobs.get(project.owner(), new JobId(UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        NotFoundException.class, ex -> assertThat(ex.code()).isEqualTo("job-not-found"));
    }

    private static JobId join(Future<JobId> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } catch (ExecutionException ex) {
            throw new AssertionError(ex.getCause());
        }
    }
}
