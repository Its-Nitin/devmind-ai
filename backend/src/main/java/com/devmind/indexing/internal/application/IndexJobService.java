package com.devmind.indexing.internal.application;

import com.devmind.identity.UserId;
import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.RefResolution;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshotProvider;
import com.devmind.job.JobApi;
import com.devmind.job.JobRef;
import com.devmind.job.JobType;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectApi;
import com.devmind.project.ProjectId;
import com.devmind.project.SourceType;
import com.devmind.shared.UnavailableException;
import com.devmind.shared.UnprocessableException;
import org.springframework.stereotype.Service;

/**
 * Submits INDEX jobs. No transaction here: resolving a ref reads Git (SPRING-2); each call below has its own.
 */
@Service
public class IndexJobService {

    private final ProjectAccessPolicy accessPolicy;
    private final ProjectApi projects;
    private final SourceSnapshotProvider sources;
    private final JobApi jobs;
    private final IndexToolchain toolchain;

    IndexJobService(
            ProjectAccessPolicy accessPolicy,
            ProjectApi projects,
            SourceSnapshotProvider sources,
            JobApi jobs,
            IndexToolchain toolchain) {
        this.accessPolicy = accessPolicy;
        this.projects = projects;
        this.sources = sources;
        this.jobs = jobs;
        this.toolchain = toolchain;
    }

    /**
     * Queues indexing of {@code ref}, or returns the job already queued, running or done for the same commit under the
     * same index config. The ref is resolved first, so the job is keyed by the commit it names now: a moved branch
     * gets a new job, the same commit doesn't (ADR-0009).
     *
     * @throws com.devmind.shared.NotFoundException ({@code project-not-found}) if {@code user} doesn't own the
     *     project; checked before anything else, so nothing about the source leaks (SEC-1, API-8)
     * @throws UnprocessableException if the ref or source is rejected, with the {@link SourceProblem} code
     * @throws UnavailableException if the source, or the embedding model the job is keyed by, can't be reached right
     *     now
     */
    public JobRef submit(UserId user, ProjectId projectId, String ref) {
        long internalId = accessPolicy.requireOwner(user, projectId);
        ProjectSource source = ProjectSource.of(projects.get(user, projectId));
        String commit = switch (sources.resolve(source, ref)) {
            case RefResolution.Resolved(String sha) -> sha;
            case RefResolution.Failed(SourceProblem problem) ->
                throw problem.retryable()
                        ? new UnavailableException(problem.code(), problem.detail())
                        : new UnprocessableException(problem.code(), problem.detail());
        };
        // A local job indexes exactly the resolved commit; a remote one clones the ref shallowly (step 3).
        String jobRef = source.type() == SourceType.LOCAL ? commit : ref;
        return jobs.submit(
                internalId,
                JobType.INDEX,
                commit + ":" + toolchain.configHash(),
                new IndexRequest(jobRef, source).toPayload());
    }
}
