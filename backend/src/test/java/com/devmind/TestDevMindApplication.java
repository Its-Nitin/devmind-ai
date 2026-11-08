package com.devmind;

import org.springframework.boot.SpringApplication;

public class TestDevMindApplication {

    public static void main(String[] args) {
        SpringApplication.from(DevMindApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
