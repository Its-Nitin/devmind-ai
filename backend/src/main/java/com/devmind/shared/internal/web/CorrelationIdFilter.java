package com.devmind.shared.internal.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation ID (OBS-1): taken from {@code X-Correlation-Id} when well-formed, otherwise
 * generated. It's put in the MDC for logs, echoed in the response header and added to ProblemDetails. Runs before
 * the security chain so rejected requests carry it too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    static final String MDC_KEY = "correlationId";

    /** Bounded charset and length, so a client can't inject log content through the header. */
    private static final Pattern WELL_FORMED = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = resolve(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String resolve(@Nullable String incoming) {
        if (incoming != null && WELL_FORMED.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
