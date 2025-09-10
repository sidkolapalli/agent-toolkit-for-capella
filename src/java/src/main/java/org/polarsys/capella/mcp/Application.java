package org.polarsys.capella.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main Spring Boot Application for Capella Diagram Service.
 * 
 * TDD GREEN Phase: Mock implementation that passes all tests.
 * Future REFACTOR Phase: Will integrate real Capella APIs with Java 17.
 */
@SpringBootApplication
public class Application {
    
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
        System.out.println("========================================");
        System.out.println("Capella Diagram Service - GREEN Phase");
        System.out.println("Running on: http://localhost:8080");
        System.out.println("Health: http://localhost:8080/actuator/health");
        System.out.println("========================================");
    }
}