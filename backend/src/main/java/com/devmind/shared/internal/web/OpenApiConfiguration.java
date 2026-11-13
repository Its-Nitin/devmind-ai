package com.devmind.shared.internal.web;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * The OpenAPI contract's shared parts (API-10): metadata, the RFC 9457 error responses every operation can return
 * (API-3), and required properties derived from null-safety. springdoc can't infer the error responses from
 * {@link ProblemDetailsAdvice} ({@code springdoc.override-with-generic-response} is off), so they're declared here by
 * rule, and operation-specific ones (409, the login 401) on the handler methods.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    private static final String PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

    /** A relative server URL keeps the committed spec independent of where it was generated. */
    @Bean
    OpenAPI devMindOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("DevMind API")
                        .version("v1")
                        .description(
                                "Session-authenticated JSON API of DevMind AI. Errors are RFC 9457 problem details."))
                .servers(List.of(new Server().url("/")));
    }

    /** Runs last, after module customizers such as the identity module's security documentation. */
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    OpenApiCustomizer errorAndNullabilityDocumentation() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            Components components = openApi.getComponents();
            ModelConverters.getInstance(true).readAll(Problem.class).forEach(components::addSchemas);
            if (openApi.getPaths() != null) {
                openApi.getPaths()
                        .values()
                        .forEach(item -> item.readOperations().forEach(OpenApiConfiguration::documentErrors));
            }
            if (components.getSchemas() != null) {
                components.getSchemas().values().forEach(OpenApiConfiguration::requireNonNullableProperties);
            }
        };
    }

    private static void documentErrors(Operation operation) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        List<Parameter> parameters = Objects.requireNonNullElse(operation.getParameters(), List.of());
        if (operation.getRequestBody() != null || !parameters.isEmpty()) {
            responses.putIfAbsent(
                    "400",
                    new ApiResponse()
                            .description("Invalid request (code: validation-failed lists the fields in errors)"));
        }
        if (parameters.stream().anyMatch(parameter -> "path".equals(parameter.getIn()))) {
            responses.putIfAbsent(
                    "404", new ApiResponse().description("Not found, or not yours (code: <resource>-not-found)"));
        }
        responses.putIfAbsent("500", new ApiResponse().description("Unexpected error (code: internal-error)"));
        responses.forEach((status, response) -> {
            if (status.startsWith("4") || status.startsWith("5")) {
                response.setContent(new Content()
                        .addMediaType(
                                PROBLEM_JSON,
                                new MediaType().schema(new Schema<>().$ref(Problem.class.getSimpleName()))));
            }
        });
    }

    /**
     * The code is {@code @NullMarked}: a property without {@code @Nullable} is always present. springdoc marks the
     * nullable ones; this makes every other property required, so generated clients don't type it as optional.
     */
    private static void requireNonNullableProperties(Schema<?> schema) {
        var properties = schema.getProperties(); // swagger-models' raw Map<String, Schema>
        if (properties == null) {
            return;
        }
        List<String> required = properties.keySet().stream()
                .filter(name -> !isNullable(Objects.requireNonNull(properties.get(name))))
                .toList();
        schema.setRequired(required.isEmpty() ? null : required);
    }

    private static boolean isNullable(Schema<?> schema) {
        if (Boolean.TRUE.equals(schema.getNullable())
                || (schema.getTypes() != null && schema.getTypes().contains("null"))) {
            return true;
        }
        return Stream.of(schema.getOneOf(), schema.getAnyOf())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .anyMatch(OpenApiConfiguration::isNullable);
    }
}
