package com.devmind.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** SPRING-5: only health, info and metrics are exposed, even to an authenticated user. */
@IntegrationTest
@AutoConfigureMockMvc
@WithMockUser
class ActuatorExposureTest {

    private final MockMvcTester mvc;

    @Autowired
    ActuatorExposureTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @ParameterizedTest
    @ValueSource(strings = {"health", "info", "metrics"})
    void exposesOperationalEndpoint(String endpoint) {
        assertThat(mvc.get().uri("/actuator/" + endpoint)).hasStatusOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {"env", "configprops", "beans", "heapdump", "threaddump", "loggers"})
    void doesNotExposeSensitiveEndpoint(String endpoint) {
        assertThat(mvc.get().uri("/actuator/" + endpoint)).hasStatus(HttpStatus.NOT_FOUND);
    }
}
