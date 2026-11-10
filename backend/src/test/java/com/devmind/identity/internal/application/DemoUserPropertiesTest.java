package com.devmind.identity.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** SPRING-1: the seed configuration is validated at startup, so a missing env var fails fast. */
class DemoUserPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "devmind.identity.seed.email=demo@devmind.local", "devmind.identity.seed.display-name=Demo User");

    @Test
    void startupFailsWhenSeedPasswordIsMissing() {
        // When: DEVMIND_SEED_PASSWORD is unset, the local profile binds an empty string
        context.withPropertyValues("devmind.identity.seed.password=")
                .run(started -> assertThat(started)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("devmind.identity.seed")
                        .hasMessageContaining("password"));
    }

    @Test
    void startupFailsWhenSeedPasswordIsTooShort() {
        context.withPropertyValues("devmind.identity.seed.password=short")
                .run(started -> assertThat(started).hasFailed());
    }

    @Test
    void bindsValidSeedConfiguration() {
        context.withPropertyValues("devmind.identity.seed.password=long-enough-password")
                .run(started -> assertThat(started.getBean(DemoUserProperties.class))
                        .isEqualTo(new DemoUserProperties("demo@devmind.local", "Demo User", "long-enough-password")));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DemoUserProperties.class)
    static class PropertiesConfiguration {}
}
