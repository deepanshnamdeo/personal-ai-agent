package com.deepansh.agent;

import com.deepansh.agent.config.ToolProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(ToolProperties.class)
public class PersonalAiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PersonalAiAgentApplication.class, args);
    }
}
