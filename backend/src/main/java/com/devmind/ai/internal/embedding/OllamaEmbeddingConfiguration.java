package com.devmind.ai.internal.embedding;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link OllamaEmbeddingAdapter} when {@code devmind.ai.embedding.provider=ollama}. With {@code fake}, the test
 * sources supply the port instead (AI-11).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "devmind.ai.embedding", name = "provider", havingValue = "ollama")
class OllamaEmbeddingConfiguration {

    @Bean
    OllamaEmbeddingAdapter ollamaEmbeddingAdapter(EmbeddingProperties properties, MeterRegistry meters) {
        return new OllamaEmbeddingAdapter(OllamaEmbeddingAdapter.restClient(properties), properties, meters);
    }

    /** Resolves the model at startup: a wrong dimension stops the app, an unreachable server only warns. */
    @Bean
    ApplicationRunner embeddingModelCheck(OllamaEmbeddingAdapter adapter) {
        return arguments -> adapter.checkAtStartup();
    }
}
