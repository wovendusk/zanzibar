package dev.zanzibar.intelligence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The LLM-facing service: explains check decisions in English, compiles
 * English policies into namespace configs, and keeps an audit log of every
 * permission change it reads from Kafka.
 */
@SpringBootApplication
public class IntelligenceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IntelligenceServiceApplication.class, args);
    }
}
