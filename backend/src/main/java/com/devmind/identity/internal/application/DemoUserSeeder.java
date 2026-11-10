package com.devmind.identity.internal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates the demo user on {@code local} startup if it doesn't exist yet. There's no seed in tests. */
@Component
@Profile("local")
class DemoUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserSeeder.class);

    private final DemoUserProperties properties;
    private final UserRegistrationService registration;

    DemoUserSeeder(DemoUserProperties properties, UserRegistrationService registration) {
        this.properties = properties;
        this.registration = registration;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (registration.isRegistered(properties.email())) {
            return;
        }
        registration.register(properties.email(), properties.displayName(), properties.password());
        log.info("Seeded the local demo user");
    }
}
