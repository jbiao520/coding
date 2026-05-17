package com.example.codeobserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeObserverApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeObserverApplication.class, args);
    }
}
