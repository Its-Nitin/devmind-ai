package com.devmind;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import com.devmind.support.TestUsers.SignedInUser;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * TEST-9: every mapped endpoint is classified, and the classification is enforced. Adding a handler without a row
 * here fails {@link #everyMappedEndpointIsClassified()}. Extend the matrix in every milestone that adds endpoints.
 * Logout isn't a handler (it's Spring Security's logout filter), so it isn't listed.
 */
@IntegrationTest
@AutoConfigureMockMvc
class AuthorizationMatrixTest {

    /** Who may call an endpoint. */
    sealed interface Access {}

    /** Anyone, including anonymous callers. */
    record Public() implements Access {}

    /** Any signed-in user; the data returned is the caller's own. */
    record Authenticated() implements Access {}

    /** Only the owner of the project in path variable {@code projectVariable}; everyone else gets 404 (API-8). */
    record OwnerScoped(String projectVariable) implements Access {}

    record Endpoint(String method, String path) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private static final Access PUBLIC = new Public();
    private static final Access AUTHENTICATED = new Authenticated();
    private static final Access PROJECT_OWNER = new OwnerScoped("projectId");

    private static final Map<Endpoint, Access> MATRIX = Map.ofEntries(
            // identity
            entry(new Endpoint("POST", "/api/auth/login"), PUBLIC),
            entry(new Endpoint("GET", "/api/auth/csrf"), PUBLIC),
            entry(new Endpoint("GET", "/api/auth/me"), AUTHENTICATED),
            // project
            entry(new Endpoint("POST", "/api/projects"), AUTHENTICATED),
            entry(new Endpoint("GET", "/api/projects"), AUTHENTICATED),
            entry(new Endpoint("GET", "/api/projects/{projectId}"), PROJECT_OWNER),
            entry(new Endpoint("DELETE", "/api/projects/{projectId}"), PROJECT_OWNER),
            // infrastructure: Spring Boot's error controller and springdoc (step 8 decides what's public)
            entry(new Endpoint("*", "/error"), AUTHENTICATED),
            entry(new Endpoint("GET", "/v3/api-docs"), AUTHENTICATED),
            entry(new Endpoint("GET", "/v3/api-docs.yaml"), AUTHENTICATED),
            entry(new Endpoint("GET", "/v3/api-docs/swagger-config"), AUTHENTICATED),
            entry(new Endpoint("GET", "/swagger-ui.html"), AUTHENTICATED));

    private final MockMvcTester mvc;
    private final RequestMappingHandlerMapping handlerMapping;
    private final TestUsers users;

    @Autowired
    AuthorizationMatrixTest(
            MockMvcTester mvc,
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            UserRegistrationService registration) {
        this.mvc = mvc;
        this.handlerMapping = handlerMapping;
        this.users = new TestUsers(mvc, registration);
    }

    @Test
    void everyMappedEndpointIsClassified() {
        Set<Endpoint> mapped = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(AuthorizationMatrixTest::endpointsOf)
                .collect(Collectors.toSet());

        assertThat(mapped)
                .as("TEST-9: add each new endpoint to MATRIX, and remove rows for endpoints that are gone")
                .containsExactlyInAnyOrderElementsOf(MATRIX.keySet());
    }

    @TestFactory
    Stream<DynamicTest> publicEndpointsAdmitAnonymousCallers() {
        return endpointsWith(Public.class)
                .map(endpoint -> DynamicTest.dynamicTest(endpoint.toString(), () -> {
                    int status =
                            anonymous(endpoint, endpoint.path()).getResponse().getStatus();
                    assertThat(status).as(endpoint.toString()).isNotIn(401, 403);
                }));
    }

    @TestFactory
    Stream<DynamicTest> protectedEndpointsRejectAnonymousCallersWith401() {
        return MATRIX.entrySet().stream()
                .filter(row -> !(row.getValue() instanceof Public))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(Endpoint::toString))
                .map(endpoint -> DynamicTest.dynamicTest(
                        endpoint.toString(),
                        () -> assertThat(anonymous(
                                        endpoint,
                                        endpoint.path()
                                                .replaceAll(
                                                        "\\{[^}]+}",
                                                        UUID.randomUUID().toString())))
                                .hasStatus(HttpStatus.UNAUTHORIZED)
                                .bodyJson()
                                .isLenientlyEqualTo("""
                                {"code": "unauthenticated"}
                                """)));
    }

    @TestFactory
    Stream<DynamicTest> ownerScopedEndpointsServeTheOwnerAnd404EveryoneElse() {
        return endpointsWith(OwnerScoped.class)
                .map(endpoint -> DynamicTest.dynamicTest(endpoint.toString(), () -> {
                    // Given
                    OwnerScoped access = (OwnerScoped) Objects.requireNonNull(MATRIX.get(endpoint));
                    SignedInUser owner = users.signIn();
                    SignedInUser stranger = users.signIn();
                    String path = endpoint.path().replace("{" + access.projectVariable() + "}", createProject(owner));
                    assertThat(path)
                            .as("only the project variable is supported so far")
                            .doesNotContain("{");

                    // When / Then: the stranger first, so a DELETE by the owner can't hide a leak
                    assertThat(signedIn(endpoint, path, stranger))
                            .as("%s by another user", endpoint)
                            .hasStatus(HttpStatus.NOT_FOUND);
                    assertThat(signedIn(endpoint, path, owner))
                            .as("%s by the owner", endpoint)
                            .hasStatus2xxSuccessful();
                }));
    }

    private static Stream<Endpoint> endpointsOf(RequestMappingInfo info) {
        Set<String> methods =
                info.getMethodsCondition().getMethods().stream().map(Enum::name).collect(Collectors.toSet());
        Set<String> verbs = methods.isEmpty() ? Set.of("*") : methods;
        return info.getPatternValues().stream().flatMap(path -> verbs.stream().map(verb -> new Endpoint(verb, path)));
    }

    private static Stream<Endpoint> endpointsWith(Class<? extends Access> access) {
        return MATRIX.entrySet().stream()
                .filter(row -> access.isInstance(row.getValue()))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(Endpoint::toString));
    }

    /** With a CSRF token and an empty JSON body, so the outcome depends on authentication only. */
    private MvcTestResult anonymous(Endpoint endpoint, String path) {
        return mvc.method(httpMethod(endpoint))
                .uri(path)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();
    }

    private MvcTestResult signedIn(Endpoint endpoint, String path, SignedInUser user) {
        return mvc.method(httpMethod(endpoint))
                .uri(path)
                .with(csrf())
                .session(user.session())
                .exchange();
    }

    private String createProject(SignedInUser owner) {
        MvcTestResult created = mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "matrix-%s", "sourceType": "LOCAL", "sourceLocation": "/repos/matrix"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return Objects.requireNonNull(created.getResponse().getHeader("Location"))
                .substring("/api/projects/".length());
    }

    private static HttpMethod httpMethod(Endpoint endpoint) {
        return endpoint.method().equals("*") ? HttpMethod.GET : HttpMethod.valueOf(endpoint.method());
    }
}
