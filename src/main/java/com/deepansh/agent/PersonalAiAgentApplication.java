package com.deepansh.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class PersonalAiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersonalAiAgentApplication.class, args);
    }
}
