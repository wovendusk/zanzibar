package dev.zanzibar.leopard.service;

import dev.zanzibar.leopard.LeopardIndex;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LeopardServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeopardServiceApplication.class, args);
    }

    @Bean
    public LeopardIndex leopardIndex() {
        return new LeopardIndex("group", "member");
    }
}
