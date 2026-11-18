package com.devmind.indexing.internal.web;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.GitFixture;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import com.devmind.support.TestUsers.SignedInUser;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /api/projects/{projectId}/index-jobs} (M2 step 7, API-4, SEC-1). */
@IntegrationTest
@AutoConfigureMockMvc
class IndexJobControllerTest {

    @TempDir
    Path temp; // under java.io.tmpdir, the test profile's allowed root

    private final MockMvcTester mvc;
    private final JsonMapper json;
    private final SignedInUser owner;
    private final SignedInUser stranger;
    private @Nullable GitFixture repo;
    private @Nullable RevCommit initial;

    @Autowired
    IndexJobControllerTest(MockMvcTester mvc, JsonMapper json, UserRegistrationService registration) {
        this.mvc = mvc;
        this.json = json;
        TestUsers users = new TestUsers(mvc, registration);
        this.owner = users.signIn();
        this.stranger = users.signIn();
    }

    @AfterEach
    void closeRepository() {
        if (repo != null) {
            repo.close();
        }
    }

    @Test
    void queuesAnIndexJobAndPointsAtIt() {
        // Given
        String project = projectWithRepository();

        // When
        MvcTestResult result = start(owner, project, "main");

        // Then: API-4
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        String jobId = jobIdOf(result);
        assertThat(result).hasHeader("Location", "/api/jobs/" + jobId);
        assertThat(mvc.get().uri("/api/jobs/" + jobId).session(owner.session()).exchange())
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "INDEX", "status": "QUEUED"}
                        """);
    }

    @Test
    void returnsTheSameJobForTheSameCommitAndANewOneOnceTheBranchMoves() {
        // Given
        String project = projectWithRepository();
        String first = jobIdOf(start(owner, project, "main"));

        // When / Then: "main" and the commit's own SHA name the same commit
        assertThat(jobIdOf(start(owner, project, "main"))).isEqualTo(first);
        assertThat(jobIdOf(start(owner, project, headSha()))).isEqualTo(first);
        Objects.requireNonNull(repo).commit("main", tree().file("README.md", "moved\n"));
        assertThat(jobIdOf(start(owner, project, "main"))).isNotEqualTo(first);
    }

    @ParameterizedTest
    @CsvSource({"main~1, ref-invalid", "develop, ref-not-found", "deadbeef, ref-not-found"})
    void rejectsRefsThatNameNoCommitWith422(String ref, String code) {
        // Given
        String project = projectWithRepository();

        // When / Then
        assertThat(start(owner, project, ref))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "%s"}
                        """.formatted(code));
    }

    @Test
    void rejectsASourceOutsideTheAllowedRootsWith422() {
        // Given
        String project = createProject("/repos/not-allowed");

        // When / Then
        assertThat(start(owner, project, "main"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "source-location-not-allowed"}
                        """);
    }

    @Test
    void validatesTheRef() {
        assertThat(start(owner, projectWithRepository(), "")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(start(owner, projectWithRepository(), "x".repeat(201))).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void answersAStrangerWith404BeforeLookingAtTheSource() {
        // Given: even a ref that would be rejected must not reveal anything
        String project = projectWithRepository();

        // When / Then
        assertThat(start(stranger, project, "main"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "project-not-found"}
                        """);
        assertThat(start(stranger, project, "develop")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(start(owner, UUID.randomUUID().toString(), "main")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private String projectWithRepository() {
        if (repo == null) {
            repo = GitFixture.init(temp.resolve("app"));
            initial = repo.commit("main", tree().file("README.md", "# App\n"));
        }
        return createProject(temp.resolve("app").toString());
    }

    private String headSha() {
        return Objects.requireNonNull(initial, "repository not created").name();
    }

    private String createProject(String location) {
        MvcTestResult created = mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "index-%s", "sourceType": "LOCAL", "sourceLocation": "%s"}
                        """.formatted(UUID.randomUUID(), location.replace("\\", "\\\\")))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return Objects.requireNonNull(created.getResponse().getHeader("Location"))
                .substring("/api/projects/".length());
    }

    private MvcTestResult start(SignedInUser user, String project, String ref) {
        return mvc.post()
                .uri("/api/projects/" + project + "/index-jobs")
                .with(csrf())
                .session(user.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"ref": "%s"}
                        """.formatted(ref))
                .exchange();
    }

    private String jobIdOf(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        try {
            return json.readTree(result.getResponse().getContentAsString())
                    .get("jobId")
                    .asString();
        } catch (UnsupportedEncodingException ex) {
            throw new AssertionError(ex);
        }
    }
}
