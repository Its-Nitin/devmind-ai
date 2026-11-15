package com.devmind.job.internal.application;

import com.devmind.support.MutableClock;
import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Job tests control time and the handler; import next to {@code @IntegrationTest} (one shared context). */
@TestConfiguration(proxyBeanMethods = false)
class JobTestConfiguration {

    static final Instant START = Instant.parse("2026-11-15T10:00:00Z");

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(START);
    }

    @Bean
    ScriptedJobHandler scriptedJobHandler() {
        return new ScriptedJobHandler();
    }
}
