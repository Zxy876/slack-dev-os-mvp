package com.asyncaiflow.worker.repository;

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
@Profile("repository-worker")
@EnableConfigurationProperties(RepositoryWorkerProperties.class)
public class RepositoryWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(RepositoryWorkerApplication.class)
                .profiles("repository-worker")
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Bean
    @SuppressWarnings("unused")
    ApplicationRunner repositoryWorkerRunner(RepositoryWorkerProperties properties, ObjectMapper objectMapper) {
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
            RepositoryWorkerProperties.RepositoryProperties repository = properties.getRepository();
            RepositoryWorkerProperties.SemanticProperties semantic = properties.getSemantic();
            RepositoryWorkerProperties.ZreadProperties zread = semantic.getZread();

            ZreadMcpClient zreadMcpClient = null;
            if (zread != null && zread.isEnabled()) {
                zreadMcpClient = new ZreadMcpClient(
                        objectMapper,
                        zread.getEndpoint(),
                        zread.getAuthorization(),
                        Duration.ofMillis(Math.max(1000L, zread.getTimeoutMillis())),
                        zread.getToolNames());
            }

            WorkerLoop workerLoop = new WorkerLoop(
                    workerConfig,
                    client,
                    new RepositoryWorkerActionHandler(
                            objectMapper,
                            Path.of(repository.getWorkspaceRoot()),
                            schemaValidator,
                            properties.getValidation().getMode(),
                            repository.getMaxSearchResults(),
                            repository.getMaxReadBytes(),
                            repository.getIgnoredDirectories(),
                            semantic.getDefaultTopK(),
                            semantic.getMaxContextFiles(),
                            semantic.getMaxCharsPerFile(),
                            zreadMcpClient)
            );
            workerLoop.runForever();
        };
    }
}