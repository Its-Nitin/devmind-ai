package com.devmind.shared.internal.web;

import com.devmind.shared.ConflictException;
import com.devmind.shared.DomainException;
import com.devmind.shared.NotFoundException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one place exceptions become RFC 9457 ProblemDetails (API-3, ERR-1). Every problem gets a stable
 * {@code type} URI and {@code code}, plus the request's {@code correlationId}. Stack traces and exception messages of
 * unexpected errors never reach the client.
 */
@RestControllerAdvice
class ProblemDetailsAdvice extends ResponseEntityExceptionHandler {

    static final String TYPE_BASE = "https://devmind.dev/problems/";
    static final String VALIDATION_FAILED = "validation-failed";
    static final String INTERNAL_ERROR = "internal-error";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsAdvice.class);

    /** One invalid input; {@code field} is the request property path. */
    record FieldViolation(String field, String message) {}

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return domainProblem(HttpStatus.NOT_FOUND, ex, request);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return domainProblem(HttpStatus.CONFLICT, ex, request);
    }

    /** JAVA-8 boundary: log once with the stack trace, answer with a generic 500. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex; // Spring Security's ExceptionTranslationFilter answers these (401/403).
        }
        log.error("Unhandled exception", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Quote the correlation ID when reporting it.");
        body.setProperty("code", INTERNAL_ERROR);
        return createResponseEntity(body, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(ProblemDetailsAdvice::toViolation)
                .toList();
        return validationProblem(ex.getBody(), violations, headers, status, request, ex);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> {
                    String parameter = parameterNameOf(result);
                    return result.getResolvableErrors().stream()
                            .map(error -> new FieldViolation(parameter, messageOf(error.getDefaultMessage())));
                })
                .toList();
        return validationProblem(ex.getBody(), violations, headers, status, request, ex);
    }

    /** Every ProblemDetail, ours or Spring MVC's, passes through here and gets the DevMind fields. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            enrich(problem, statusCode);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> domainProblem(HttpStatus status, DomainException ex, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        body.setProperty("code", ex.code());
        return createResponseEntity(body, new HttpHeaders(), status, request);
    }

    private @Nullable ResponseEntity<Object> validationProblem(
            ProblemDetail body,
            List<FieldViolation> violations,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request,
            Exception ex) {
        body.setDetail("The request has invalid fields.");
        body.setProperty("code", VALIDATION_FAILED);
        body.setProperty("errors", violations);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static void enrich(ProblemDetail problem, HttpStatusCode statusCode) {
        String code = codeOf(problem, statusCode);
        problem.setProperty("code", code);
        problem.setType(URI.create(TYPE_BASE + code));
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    /** Our handlers set an explicit code; Spring MVC's problems get one derived from the status, e.g. not-found. */
    private static String codeOf(ProblemDetail problem, HttpStatusCode statusCode) {
        var properties = problem.getProperties();
        if (properties != null && properties.get("code") instanceof String explicit) {
            return explicit;
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null
                ? "http-" + statusCode.value()
                : status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), messageOf(error.getDefaultMessage()));
    }

    private static String parameterNameOf(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name == null ? "parameter" : name;
    }

    private static String messageOf(@Nullable String message) {
        return message == null ? "is invalid" : message;
    }
}
