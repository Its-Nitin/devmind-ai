package com.devmind.ai.internal.embedding;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The embedding model and how to reach it, {@code devmind.ai.embedding.*} (SPRING-1). Defaults in {@code
 * application.yaml} are the M0 decision: {@code mxbai-embed-large}, 1024 dimensions, with its query prefix
 * (ADR-0006).
 *
 * @param provider which adapter serves {@link com.devmind.ai.EmbeddingPort}
 * @param baseUrl the model server's base URL, from server config only
 * @param model the model name; without a {@code :tag}, Ollama's {@code :latest} is meant
 * @param dimensions the vector length the schema stores; the adapter refuses a model that returns another
 * @param documentPrefix prepended to every document text, often empty
 * @param queryPrefix prepended to every query text
 * @param batchSize texts sent per model request
 * @param connectTimeout limit for opening a connection (SPRING-6)
 * @param readTimeout limit for one response, so it must cover a whole batch on CPU (ERR-6)
 */
@Validated
@ConfigurationProperties("devmind.ai.embedding")
record EmbeddingProperties(
        @NotNull Provider provider,
        @NotNull URI baseUrl,
        @NotBlank String model,
        @Min(1) @Max(16_000) int dimensions,
        @DefaultValue("") String documentPrefix,
        @DefaultValue("") String queryPrefix,
        @Min(1) @Max(512) int batchSize,
        @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) Duration readTimeout) {

    /** The adapter behind the port. */
    enum Provider {
        /** {@link OllamaEmbeddingAdapter}. */
        OLLAMA,
        /** No adapter from main code: the test sources register a deterministic fake (AI-11). */
        FAKE
    }

    @AssertTrue(message = "base-url must be an absolute http or https URL without user info, query or fragment")
    boolean isBaseUrlValid() {
        // Null when @NotNull above already failed; that violation is the one to report.
        if (baseUrl == null) {
            return true;
        }
        String scheme = baseUrl.getScheme();
        return ("http".equals(scheme) || "https".equals(scheme))
                && baseUrl.getHost() != null
                && baseUrl.getRawUserInfo() == null
                && baseUrl.getRawQuery() == null
                && baseUrl.getRawFragment() == null;
    }
}
