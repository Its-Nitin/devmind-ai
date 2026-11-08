package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

/** SPRING-3: no open session in view, and Hibernate only validates the Flyway-owned schema. */
@IntegrationTest
class PersistenceSettingsTest {

    private final Environment environment;
    private final ApplicationContext context;

    @Autowired
    PersistenceSettingsTest(Environment environment, ApplicationContext context) {
        this.environment = environment;
        this.context = context;
    }

    @Test
    void openInViewIsDisabled() {
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");
        assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class))
                .isEmpty();
    }

    @Test
    void hibernateOnlyValidatesTheSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
}
