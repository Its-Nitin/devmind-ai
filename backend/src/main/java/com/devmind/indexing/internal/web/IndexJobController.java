package com.devmind.indexing.internal.web;

import com.devmind.identity.CurrentUser;
import com.devmind.indexing.internal.application.IndexJobService;
import com.devmind.job.JobRef;
import com.devmind.project.ProjectId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Starting index runs (ARCH-9: validate, call one service method, map). */
@RestController
@Tag(name = "Indexing")
class IndexJobController {

    private final IndexJobService indexJobs;
    private final CurrentUser currentUser;

    IndexJobController(IndexJobService indexJobs, CurrentUser currentUser) {
        this.indexJobs = indexJobs;
        this.currentUser = currentUser;
    }

    /**
     * @param ref a branch, a tag, or a commit SHA (full, or a prefix of at least 7 characters)
     */
    record StartIndexRequest(@NotBlank @Size(max = 200) String ref) {}

    /** The queued (or already existing) index job; poll it at {@code Location}. */
    record IndexJobResponse(UUID jobId) {}

    @PostMapping("/api/projects/{projectId}/index-jobs")
    @Operation(
            summary = "Index the project at a ref",
            description = "Resolves the ref to a commit and queues an index job for it. Asking again for the same "
                    + "commit returns the same job, so a repeat is a no-op.")
    @ApiResponse(
            responseCode = "202",
            description = "Queued, or already queued or done for this commit",
            headers =
                    @Header(
                            name = "Location",
                            description = "Path of the job to poll",
                            schema = @Schema(type = "string", format = "uri-reference")))
    @ApiResponse(
            responseCode = "422",
            description = "The ref or source can't be indexed (code: ref-invalid, ref-not-found, ref-ambiguous, "
                    + "source-location-not-allowed, source-location-not-found, source-not-a-repository, "
                    + "source-url-not-allowed)")
    @ApiResponse(
            responseCode = "503",
            description = "The source can't be reached right now; try again (code: source-unreachable, source-timeout)")
    ResponseEntity<IndexJobResponse> startIndexJob(
            @PathVariable UUID projectId, @Valid @RequestBody StartIndexRequest request) {
        JobRef job = indexJobs.submit(currentUser.id(), new ProjectId(projectId), request.ref());
        URI location = UriComponentsBuilder.fromPath("/api/jobs/{jobId}")
                .buildAndExpand(job.id().value())
                .toUri();
        return ResponseEntity.accepted()
                .location(location)
                .body(new IndexJobResponse(job.id().value()));
    }
}
