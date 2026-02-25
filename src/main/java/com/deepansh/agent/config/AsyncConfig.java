package com.deepansh.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated thread pool for async tasks (memory extraction).
 *
 * Isolated from the web thread pool so a spike in memory extraction
 * (e.g. many concurrent agent runs) never starves HTTP request handling.
 *
 * Sizing rationale:
 * - Memory extraction is I/O bound (one LLM call per session end)
 * - Core=2, Max=5 is sufficient for a personal agent
 * - Queue capacity=50 provides backpressure without dropping tasks
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "memoryTaskExecutor")
    public Executor memoryTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("memory-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
