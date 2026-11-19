package com.devmind.indexing.internal.application;

import com.devmind.ingestion.ProjectSource;
import com.devmind.job.JobOutcome;
import com.devmind.project.SourceType;
import java.nio.file.Path;
import org.springframework.context.ApplicationContext;

/**
 * Indexes a fixture repository for tests outside this package, by running the INDEX handler directly, as the worker
 * would. Lives here because the handler is package-private.
 */
public final class IndexFixtures {

    private IndexFixtures() {}

    /**
     * Indexes {@code ref} of the local repository at {@code repository} into project {@code projectId}.
     *
     * @throws AssertionError unless the run succeeds
     */
    public static void index(ApplicationContext context, long projectId, Path repository, String ref) {
        IndexRevisionJobHandler handler = context.getBean(IndexRevisionJobHandler.class);
        JobOutcome outcome = handler.handle(FakeJobContext.of(
                projectId,
                new IndexRequest(ref, new ProjectSource(SourceType.LOCAL, repository.toString())).toPayload()));
        if (!(outcome instanceof JobOutcome.Succeeded)) {
            throw new AssertionError("indexing failed: " + outcome);
        }
    }
}
