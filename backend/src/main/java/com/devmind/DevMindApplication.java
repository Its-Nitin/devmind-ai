package com.devmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan // SPRING-1: typed, validated devmind.<module> properties records
public class DevMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevMindApplication.class, args);
    }
}
