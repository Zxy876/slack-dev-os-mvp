package com.asyncaiflow.worker.planner;

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
@Profile("planner-worker")
@EnableConfigurationProperties(PlannerWorkerProperties.class)
public class PlannerWorkerApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(PlannerWorkerApplication.class)
                .profiles("planner-worker")
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Bean
    @SuppressWarnings("unused")
    ApplicationRunner plannerWorkerRunner(PlannerWorkerProperties properties, ObjectMapper objectMapper) {
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
            WorkerLoop workerLoop = new WorkerLoop(
                    workerConfig,
                    client,
                    new PlannerWorkerActionHandler(objectMapper)
            );
            workerLoop.runForever();
        };
    }
}
