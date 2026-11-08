package com.devmind;

import com.devmind.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/** Runs the app against a Testcontainers database: {@code ./mvnw spring-boot:test-run}. */
public class TestDevMindApplication {

    public static void main(String[] args) {
        SpringApplication.from(DevMindApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
