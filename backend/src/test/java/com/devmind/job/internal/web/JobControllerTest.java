package com.devmind.job.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.job.JobApi;
import com.devmind.job.JobType;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectId;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import com.devmind.support.TestUsers.SignedInUser;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** {@code GET /api/jobs/{jobId}}: the response contract (API-5, API-9) and 404 for anyone but the owner (API-8). */
@IntegrationTest
@AutoConfigureMockMvc
class JobControllerTest {

    private final MockMvcTester mvc;
    private final TestUsers users;
    private final JobApi jobs;
    private final ProjectAccessPolicy accessPolicy;

    @Autowired
    JobControllerTest(
            MockMvcTester mvc, UserRegistrationService registration, JobApi jobs, ProjectAccessPolicy accessPolicy) {
        this.mvc = mvc;
        this.users = new TestUsers(mvc, registration);
        this.jobs = jobs;
        this.accessPolicy = accessPolicy;
    }

    @Test
    void ownerGetsStatusAndProgress() {
        // Given
        SignedInUser owner = users.signIn();
        String jobId = submitJob(owner);

        // When
        MvcTestResult result = mvc.get()
                .uri("/api/jobs/{jobId}", jobId)
                .session(owner.session())
                .exchange();

        // Then
        assertThat(result).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"jobId": "%s", "type": "INDEX", "status": "QUEUED", "attempts": 0, "errorCode": null,
                 "progress": {"phase": null, "done": 0, "total": 0}}
                """.formatted(jobId));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.updatedAt")
                .asString()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"); // API-9: ISO-8601 UTC
    }

    @Test
    void anotherUsersJobAndAMissingJobLookTheSame() {
        // Given
        String jobId = submitJob(users.signIn());
        SignedInUser stranger = users.signIn();

        // When
        MvcTestResult foreign = mvc.get()
                .uri("/api/jobs/{jobId}", jobId)
                .session(stranger.session())
                .exchange();
        MvcTestResult missing = mvc.get()
                .uri("/api/jobs/{jobId}", UUID.randomUUID())
                .session(stranger.session())
                .exchange();

        // Then
        for (MvcTestResult result : new MvcTestResult[] {foreign, missing}) {
            assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().isLenientlyEqualTo("""
                    {"code": "job-not-found", "detail": "Job not found."}
                    """);
        }
    }

    @Test
    void rejectsAJobIdThatIsNotAUuid() {
        SignedInUser user = users.signIn();

        assertThat(mvc.get().uri("/api/jobs/not-a-uuid").session(user.session()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private String submitJob(SignedInUser owner) {
        MvcTestResult created = mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "jobs-%s", "sourceType": "LOCAL", "sourceLocation": "/repos/jobs"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID projectId =
                UUID.fromString(Objects.requireNonNull(created.getResponse().getHeader("Location"))
                        .substring("/api/projects/".length()));
        long internalId = accessPolicy.requireOwner(owner.id(), new ProjectId(projectId));
        return jobs.submit(internalId, JobType.INDEX, "k", Map.of())
                .id()
                .value()
                .toString();
    }
}
