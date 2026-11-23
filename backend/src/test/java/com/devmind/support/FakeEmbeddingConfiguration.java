package com.devmind.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The embedding port for integration tests: {@link FakeEmbedding}, never a model (AI-11). The {@code test} profile
 * sets {@code devmind.ai.embedding.provider=fake}, so main code registers no adapter next to it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    @Bean
    FakeEmbedding fakeEmbedding() {
        return new FakeEmbedding();
    }
}
