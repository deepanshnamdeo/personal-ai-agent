package com.deepansh.agent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * Enable MongoDB auditing so @CreatedDate and @LastModifiedDate
 * are automatically populated on document save.
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = {
    "com.deepansh.agent.memory",
    "com.deepansh.agent.observability"
})
public class MongoConfig {
}
