package com.devmind.shared.internal.web;

import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The documented shape of every error response, for the OpenAPI spec only: {@link ProblemDetailsAdvice} builds the
 * real body as a {@code ProblemDetail}. Keep the two in sync; {@code ProblemDetailsTest} pins the runtime side.
 *
 * @param type {@code https://devmind.dev/problems/<code>}
 * @param code stable machine-readable error code, e.g. {@code project-not-found} (API-3)
 * @param correlationId quote it when reporting an error (OBS-1)
 * @param errors the invalid fields, on {@code validation-failed} only
 */
record Problem(
        URI type,
        String title,
        int status,
        String detail,
        @Nullable URI instance,
        String code,
        @Nullable String correlationId,
        @Nullable List<ProblemDetailsAdvice.FieldViolation> errors) {}
