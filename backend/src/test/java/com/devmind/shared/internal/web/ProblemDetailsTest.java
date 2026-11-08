package com.devmind.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.shared.ConflictException;
import com.devmind.shared.NotFoundException;
import com.devmind.support.IntegrationTest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** API-3 / ERR-1: every failure is a ProblemDetail with a stable type, code and the correlation ID. */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
@Import(ProblemDetailsTest.ProbeController.class)
class ProblemDetailsTest {

    private static final String CORRELATION_ID = "test-correlation-0001";

    private final MockMvcTester mvc;

    @Autowired
    ProblemDetailsTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void unknownEndpointReturnsNotFoundProblemWithCorrelationId() {
        // When
        MvcTestResult result = mvc.get()
                .uri("/api/does-not-exist")
                .header(CorrelationIdFilter.HEADER, CORRELATION_ID)
                .exchange();

        // Then
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(CorrelationIdFilter.HEADER, CORRELATION_ID)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "https://devmind.dev/problems/not-found", "status": 404,
                         "code": "not-found", "correlationId": "%s"}
                        """.formatted(CORRELATION_ID));
    }

    @Test
    void notFoundExceptionMapsTo404WithItsCode() {
        // When
        MvcTestResult result = mvc.get().uri("/test-probe/not-found").exchange();

        // Then
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().isLenientlyEqualTo("""
                        {"type": "https://devmind.dev/problems/thing-not-found", "status": 404,
                         "code": "thing-not-found", "detail": "Thing was not found."}
                        """);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.correlationId")
                .asString()
                .isNotBlank();
    }

    @Test
    void conflictExceptionMapsTo409WithItsCode() {
        // When
        MvcTestResult result = mvc.get().uri("/test-probe/conflict").exchange();

        // Then
        assertThat(result).hasStatus(HttpStatus.CONFLICT).bodyJson().isLenientlyEqualTo("""
                        {"type": "https://devmind.dev/problems/thing-exists", "code": "thing-exists"}
                        """);
    }

    @Test
    void invalidBodyMapsTo400WithFieldErrors() {
        // Given: name is longer than the 5-character limit
        String body = """
                {"name": "too-long-name"}
                """;

        // When
        MvcTestResult result = mvc.post()
                .uri("/test-probe/validated")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        // Then
        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "validation-failed", "errors": [{"field": "name"}]}
                        """);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.errors[0].message")
                .asString()
                .isNotBlank();
    }

    @Test
    void unexpectedExceptionMapsToGeneric500WithoutLeakingInternals() {
        // When
        MvcTestResult result = mvc.get().uri("/test-probe/boom").exchange();

        // Then
        assertThat(result)
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"code": "internal-error"}
                        """);
        assertThat(result).bodyJson().doesNotHavePath("$.trace").doesNotHavePath("$.exception");
        assertThat(result).bodyText().doesNotContain("secret internal detail", "IllegalStateException");
    }

    /** Test-only endpoints. Nested in a test class, so component scanning skips it; imported explicitly above. */
    @RestController
    static class ProbeController {

        record ProbeRequest(@NotBlank @Size(max = 5) String name) {}

        @GetMapping("/test-probe/not-found")
        void notFound() {
            throw new NotFoundException("thing-not-found", "Thing was not found.");
        }

        @GetMapping("/test-probe/conflict")
        void conflict() {
            throw new ConflictException("thing-exists", "Thing already exists.");
        }

        @GetMapping("/test-probe/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @PostMapping("/test-probe/validated")
        void validated(@Valid @RequestBody ProbeRequest request) {
            // validation is the behaviour under test
        }
    }
}
