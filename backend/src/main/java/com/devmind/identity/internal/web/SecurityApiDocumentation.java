package com.devmind.identity.internal.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Documents what springdoc can't see in the handler methods (API-10, SEC-7): the session cookie and CSRF header, which
 * operations are public ({@link SecurityConfiguration#PUBLIC_ENDPOINTS}), the 401 and 403 the filter chain answers
 * with, and logout, which is a security filter rather than a handler. Runs before the shared error documentation,
 * which gives every error response its ProblemDetail body.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class SecurityApiDocumentation implements OpenApiCustomizer {

    static final String SESSION_SCHEME = "session";
    static final String CSRF_SCHEME = "csrf";

    /** Servlet containers' default session cookie; {@code server.servlet.session.cookie.name} isn't set. */
    private static final String SESSION_COOKIE = "JSESSIONID";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private static final Set<HttpMethod> SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents()
                .addSecuritySchemes(
                        SESSION_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name(SESSION_COOKIE)
                                .description("HttpOnly session cookie set by POST " + SecurityConfiguration.LOGIN_PATH
                                        + ". Browsers send it automatically."))
                .addSecuritySchemes(
                        CSRF_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(CSRF_HEADER)
                                .description("The XSRF-TOKEN cookie's value, echoed on every unsafe request. GET "
                                        + SecurityConfiguration.CSRF_PATH + " issues the cookie."));
        if (openApi.getPaths() == null) {
            openApi.setPaths(new Paths());
        }
        openApi.getPaths().addPathItem(SecurityConfiguration.LOGOUT_PATH, new PathItem().post(logout()));
        openApi.getPaths()
                .forEach((path, item) -> item.readOperationsMap()
                        .forEach((method, operation) -> secure(
                                new SecurityConfiguration.PublicEndpoint(HttpMethod.valueOf(method.name()), path),
                                operation)));
    }

    private static Operation logout() {
        return new Operation()
                .operationId("logOut")
                .addTagsItem(AuthController.TAG)
                .summary("End the session")
                .description(
                        "Invalidates the session and clears the XSRF-TOKEN cookie. Signed-out callers get 204 too.")
                .responses(new ApiResponses().addApiResponse("204", new ApiResponse().description("Signed out")));
    }

    /** Sets the operation's security requirement and adds the responses of the filter chain. */
    private static void secure(SecurityConfiguration.PublicEndpoint endpoint, Operation operation) {
        SecurityRequirement requirement = new SecurityRequirement();
        if (!SecurityConfiguration.PUBLIC_ENDPOINTS.contains(endpoint)) {
            requirement.addList(SESSION_SCHEME);
            operation
                    .getResponses()
                    .addApiResponse("401", new ApiResponse().description("Not signed in (code: unauthenticated)"));
        }
        if (!SAFE_METHODS.contains(endpoint.method())) {
            requirement.addList(CSRF_SCHEME);
            operation
                    .getResponses()
                    .addApiResponse(
                            "403",
                            new ApiResponse().description("CSRF token missing or invalid (code: invalid-csrf-token)"));
        }
        operation.setSecurity(requirement.isEmpty() ? List.of() : List.of(requirement));
    }
}
