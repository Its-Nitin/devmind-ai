package com.devmind.job.internal.web;

import com.devmind.identity.CurrentUser;
import com.devmind.job.JobApi;
import com.devmind.job.JobId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Job status for polling (API-4: long-running operations point here). Jobs of other users' projects answer 404
 * (API-8). Method names are the OpenAPI operation IDs.
 */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Jobs")
class JobController {

    private final JobApi jobs;
    private final CurrentUser currentUser;

    JobController(JobApi jobs, CurrentUser currentUser) {
        this.jobs = jobs;
        this.currentUser = currentUser;
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "Get a job's status and progress")
    JobResponse getJob(@PathVariable UUID jobId) {
        return JobResponse.of(jobs.get(currentUser.id(), new JobId(jobId)));
    }
}
