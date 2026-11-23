package com.devmind.ai.internal.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ai.EmbeddingPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/** SPRING-1: embedding configuration is validated at startup, and the provider decides which adapter exists. */
class EmbeddingPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "devmind.ai.embedding.base-url=http://localhost:11434",
                    "devmind.ai.embedding.model=mxbai-embed-large",
                    "devmind.ai.embedding.dimensions=1024",
                    "devmind.ai.embedding.query-prefix=Represent this sentence for searching relevant passages: ",
                    "devmind.ai.embedding.batch-size=16",
                    "devmind.ai.embedding.connect-timeout=5s",
                    "devmind.ai.embedding.read-timeout=2m");

    @Test
    void wiresTheOllamaAdapterForTheOllamaProvider() {
        // Runners don't run in ApplicationContextRunner, so no model server is contacted.
        context.withPropertyValues("devmind.ai.embedding.provider=ollama")
                .run(started -> assertThat(started)
                        .hasNotFailed()
                        .hasSingleBean(EmbeddingPort.class)
                        .getBean(EmbeddingPort.class)
                        .isInstanceOf(OllamaEmbeddingAdapter.class));
    }

    @Test
    void wiresNoAdapterForTheFakeProvider() {
        context.withPropertyValues("devmind.ai.embedding.provider=fake")
                .run(started -> assertThat(started).hasNotFailed().doesNotHaveBean(EmbeddingPort.class));
    }

    @Test
    void shipsTheM0ModelWithItsExactQueryPrefix() {
        // Given: the real application.yaml, because test property values are trimmed and would hide a lost space
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .withInitializer(started -> loadApplicationYaml(started.getEnvironment()))
                .run(started -> {
                    // Then: ADR-0006's model, dimension and prefixes, character for character
                    EmbeddingProperties properties = started.getBean(EmbeddingProperties.class);
                    assertThat(properties.provider()).isEqualTo(EmbeddingProperties.Provider.OLLAMA);
                    assertThat(properties.model()).isEqualTo("mxbai-embed-large");
                    assertThat(properties.dimensions()).isEqualTo(1024);
                    assertThat(properties.queryPrefix())
                            .isEqualTo("Represent this sentence for searching relevant passages: ");
                    assertThat(properties.documentPrefix()).isEmpty();
                });
    }

    private static void loadApplicationYaml(ConfigurableEnvironment environment) {
        try {
            new YamlPropertySourceLoader()
                    .load("application.yaml", new ClassPathResource("application.yaml"))
                    .forEach(environment.getPropertySources()::addFirst);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void startupFailsForANonHttpBaseUrl() {
        context.withPropertyValues(
                        "devmind.ai.embedding.provider=ollama", "devmind.ai.embedding.base-url=file:///etc/passwd")
                .run(started ->
                        assertThat(started).hasFailed().getFailure().rootCause().hasMessageContaining("base-url"));
    }

    @Test
    void startupFailsForABaseUrlWithUserInfo() {
        context.withPropertyValues(
                        "devmind.ai.embedding.provider=ollama",
                        "devmind.ai.embedding.base-url=http://user:pw@localhost:11434")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void startupFailsForAZeroDimension() {
        context.withPropertyValues("devmind.ai.embedding.provider=ollama", "devmind.ai.embedding.dimensions=0")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void startupFailsWithoutAProvider() {
        context.run(started ->
                assertThat(started).hasFailed().getFailure().rootCause().hasMessageContaining("provider"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EmbeddingProperties.class)
    @Import(OllamaEmbeddingConfiguration.class)
    static class PropertiesConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
