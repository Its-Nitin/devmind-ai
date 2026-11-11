package com.devmind.project.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import com.devmind.support.TestUsers.SignedInUser;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Project CRUD for the owner, 404 for everyone else (API-8), validation, conflicts and paging (API-2, API-6). */
@IntegrationTest
@AutoConfigureMockMvc
class ProjectControllerTest {

    private final MockMvcTester mvc;
    private final TestUsers users;

    private final SignedInUser owner;

    @Autowired
    ProjectControllerTest(MockMvcTester mvc, UserRegistrationService registration) {
        this.mvc = mvc;
        this.users = new TestUsers(mvc, registration);
        this.owner = users.signIn(); // JUnit creates a test instance per method, so each test gets a fresh owner
    }

    @Test
    void createReturns201WithLocationAndBody() {
        // When
        MvcTestResult result =
                create(owner, "petclinic", "GIT_PUBLIC", "https://github.com/spring-petclinic/spring-petclinic-rest");

        // Then
        assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                        {"name": "petclinic", "sourceType": "GIT_PUBLIC",
                         "sourceLocation": "https://github.com/spring-petclinic/spring-petclinic-rest",
                         "defaultBranch": null}
                        """);
        String id = projectIdOf(result);
        assertThat(result).hasHeader("Location", "/api/projects/" + id);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.createdAt")
                .asString()
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z"); // API-9: ISO-8601 UTC
    }

    @Test
    void ownerCanGetAndDeleteTheirProject() {
        // Given
        String id = projectIdOf(create(owner, "mine", "LOCAL", "/repos/mine"));

        // When / Then
        assertThat(mvc.get().uri("/api/projects/{id}", id).session(owner.session()))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"projectId": "%s", "name": "mine"}
                        """.formatted(id));
        assertThat(mvc.delete().uri("/api/projects/{id}", id).with(csrf()).session(owner.session()))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/projects/{id}", id).session(owner.session()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void anotherUserGets404ForGetAndDelete() {
        // Given
        String id = projectIdOf(create(owner, "private", "LOCAL", "/repos/private"));
        SignedInUser stranger = users.signIn();

        // When
        MvcTestResult get = mvc.get()
                .uri("/api/projects/{id}", id)
                .session(stranger.session())
                .exchange();
        MvcTestResult delete = mvc.delete()
                .uri("/api/projects/{id}", id)
                .with(csrf())
                .session(stranger.session())
                .exchange();

        // Then: same answer as for a project that doesn't exist (API-8)
        String notFound = """
                {"status": 404, "code": "project-not-found", "detail": "Project not found."}
                """;
        assertThat(get).hasStatus(HttpStatus.NOT_FOUND).bodyJson().isLenientlyEqualTo(notFound);
        assertThat(delete).hasStatus(HttpStatus.NOT_FOUND).bodyJson().isLenientlyEqualTo(notFound);
        assertThat(mvc.get().uri("/api/projects/{id}", UUID.randomUUID()).session(stranger.session()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .isLenientlyEqualTo(notFound);
        assertThat(mvc.get().uri("/api/projects/{id}", id).session(owner.session()))
                .as("the stranger's delete must not have removed it")
                .hasStatusOk();
    }

    @Test
    void duplicateNameForSameOwnerIs409ButOtherOwnersMayReuseIt() {
        // Given
        create(owner, "shared-name", "LOCAL", "/repos/a");

        // When
        MvcTestResult duplicate = create(owner, "shared-name", "LOCAL", "/repos/b");
        MvcTestResult otherOwner = create(users.signIn(), "shared-name", "LOCAL", "/repos/c");

        // Then
        assertThat(duplicate).hasStatus(HttpStatus.CONFLICT).bodyJson().isLenientlyEqualTo("""
                        {"code": "project-name-taken"}
                        """);
        assertThat(otherOwner).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"""
                {"name": "", "sourceType": "LOCAL", "sourceLocation": "/repos/x"}
                """, """
                {"name": "x", "sourceLocation": "/repos/x"}
                """, """
                {"name": "x", "sourceType": "LOCAL", "sourceLocation": " "}
                """})
    void invalidCreateRequestIs400WithFieldErrors(String body) {
        // When
        MvcTestResult result = mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        // Then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().isLenientlyEqualTo("""
                        {"code": "validation-failed"}
                        """);
        assertThat(result).bodyJson().extractingPath("$.errors").asArray().hasSize(1);
    }

    @Test
    void nameLongerThan100CharactersIs400() {
        // When
        MvcTestResult result = create(owner, "n".repeat(101), "LOCAL", "/repos/x");

        // Then
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "name"}]}
                        """);
    }

    @Test
    void unknownSourceTypeIs400() {
        assertThat(create(owner, "x", "SVN", "/repos/x")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void listShowsOnlyOwnProjectsPagedAndSorted() {
        // Given
        create(owner, "bravo", "LOCAL", "/repos/b");
        create(owner, "alpha", "LOCAL", "/repos/a");
        create(owner, "charlie", "LOCAL", "/repos/c");
        create(users.signIn(), "someone-elses", "LOCAL", "/repos/z");

        // When
        MvcTestResult firstPage = mvc.get()
                .uri("/api/projects?page=0&size=2&sort=name,asc")
                .session(owner.session())
                .exchange();

        // Then
        assertThat(firstPage).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                        {"content": [{"name": "alpha"}, {"name": "bravo"}],
                         "page": {"size": 2, "number": 0, "totalElements": 3, "totalPages": 2}}
                        """);
        assertThat(mvc.get().uri("/api/projects?page=1&size=2&sort=name,asc").session(owner.session()))
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"content": [{"name": "charlie"}]}
                        """);
    }

    @Test
    void emptyListHasEmptyContentNotNull() {
        assertThat(mvc.get().uri("/api/projects").session(owner.session()))
                .hasStatusOk()
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"content": [], "page": {"totalElements": 0}}
                        """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"size=101", "size=0", "page=-1", "sort=sourceLocation", "sort=name,sideways", "sort=id"})
    void pagingParametersOutsideTheWhitelistAre400(String query) {
        assertThat(mvc.get().uri("/api/projects?" + query).session(owner.session()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "validation-failed"}
                        """);
    }

    @Test
    void malformedProjectIdIs400() {
        assertThat(mvc.get().uri("/api/projects/not-a-uuid").session(owner.session()))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult create(SignedInUser user, String name, String sourceType, String sourceLocation) {
        return mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(user.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "%s", "sourceType": "%s", "sourceLocation": "%s"}
                        """.formatted(name, sourceType, sourceLocation))
                .exchange();
    }

    private static String projectIdOf(MvcTestResult created) {
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String location = Objects.requireNonNull(created.getResponse().getHeader("Location"));
        assertThat(location).startsWith("/api/projects/");
        return location.substring("/api/projects/".length());
    }
}
