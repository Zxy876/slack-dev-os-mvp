package com.asyncaiflow.worker.git;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.sdk.AsyncAiFlowWorkerClient;
import com.asyncaiflow.worker.sdk.WorkerConfig;
import com.asyncaiflow.worker.sdk.WorkerLoop;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        RedisAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
})
@Profile("git-worker")
@EnableConfigurationProperties(GitWorkerProperties.class)
public class GitWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(GitWorkerApplication.class)
                .profiles("git-worker")
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Bean
    @SuppressWarnings("unused")
    ApplicationRunner gitWorkerRunner(GitWorkerProperties properties, ObjectMapper objectMapper) {
        return args -> {
            WorkerConfig workerConfig = new WorkerConfig(
                    properties.getServerBaseUrl(),
                    properties.getWorkerId(),
                    properties.getCapabilities(),
                    Duration.ofMillis(Math.max(200L, properties.getPollIntervalMillis())),
                    Duration.ofMillis(Math.max(1000L, properties.getHeartbeatIntervalMillis())),
                    properties.getMaxActions()
            );

            AsyncAiFlowWorkerClient client = new AsyncAiFlowWorkerClient(workerConfig.serverBaseUrl());
            ActionSchemaResolver schemaResolver = new ActionSchemaResolver(
                    properties.getValidation().getSchemaBasePath());
            ActionSchemaValidator schemaValidator = new ActionSchemaValidator(objectMapper, schemaResolver);
            GitWorkerProperties.RepositoryProperties repository = properties.getRepository();

            WorkerLoop workerLoop = new WorkerLoop(
                    workerConfig,
                    client,
                    new GitWorkerActionHandler(
                            objectMapper,
                            Path.of(repository.getWorkspaceRoot()),
                            schemaValidator,
                            properties.getValidation().getMode(),
                            repository.getMaxPatchBytes(),
                            repository.getMaxCommandOutputBytes(),
                            repository.getCommandTimeoutMillis())
            );
            workerLoop.runForever();
        };
    }
}
