package dev.zanzibar.acl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The authoritative service: owns the tuple store and answers check, read,
 * write and expand. @EnableScheduling turns on the outbox publisher's timer.
 */
@SpringBootApplication
@EnableScheduling
public class AclServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AclServiceApplication.class, args);
    }
}
