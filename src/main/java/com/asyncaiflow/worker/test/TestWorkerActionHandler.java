package com.asyncaiflow.worker.test;

import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TestWorkerActionHandler implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestWorkerActionHandler.class);

    private final ObjectMapper objectMapper;
    private final TestWorkerProperties.TestActionProperties properties;

    public TestWorkerActionHandler(ObjectMapper objectMapper, TestWorkerProperties.TestActionProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) throws Exception {
        if (!"test_action".equals(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "TestWorker only supports capability test_action"
            );
        }

        TestActionPayload payload = parsePayload(assignment.payload());
        int sleepSeconds = resolveSleepSeconds(payload);
        LOGGER.info("Executing test_action actionId={} sleepSeconds={} retryCount={} leaseExpireAt={}",
                assignment.actionId(), sleepSeconds, assignment.retryCount(), assignment.leaseExpireAt());

        Thread.sleep(sleepSeconds * 1000L);

        String forcedResult = payload.forceResult();
        if (forcedResult != null && !forcedResult.isBlank()) {
            if ("SUCCEEDED".equalsIgnoreCase(forcedResult)) {
                return WorkerExecutionResult.succeeded("forced success after " + sleepSeconds + "s");
            }
            return WorkerExecutionResult.failed("forced failure after " + sleepSeconds + "s", "forced by payload");
        }

        double successRate = resolveSuccessRate(payload);
        boolean success = ThreadLocalRandom.current().nextDouble() < successRate;
        if (success) {
            return WorkerExecutionResult.succeeded("random success after " + sleepSeconds + "s");
        }
        return WorkerExecutionResult.failed("random failure after " + sleepSeconds + "s", "simulated random failure");
    }

    private int resolveSleepSeconds(TestActionPayload payload) {
        if (payload.sleepSeconds() != null && payload.sleepSeconds() > 0) {
            return payload.sleepSeconds();
        }

        int minSeconds = Math.max(1, properties.getMinSleepSeconds());
        int maxSeconds = Math.max(minSeconds, properties.getMaxSleepSeconds());
        if (minSeconds == maxSeconds) {
            return minSeconds;
        }
        return ThreadLocalRandom.current().nextInt(minSeconds, maxSeconds + 1);
    }

    private double resolveSuccessRate(TestActionPayload payload) {
        Double payloadSuccessRate = payload.successRate();
        double configured = payloadSuccessRate != null ? payloadSuccessRate : properties.getSuccessRate();
        if (configured < 0D) {
            return 0D;
        }
        if (configured > 1D) {
            return 1D;
        }
        return configured;
    }

    private TestActionPayload parsePayload(String rawPayload) {
        if (rawPayload == null || rawPayload.isBlank()) {
            return TestActionPayload.empty();
        }

        try {
            return objectMapper.readValue(rawPayload, TestActionPayload.class);
        } catch (RuntimeException | java.io.IOException exception) {
            LOGGER.warn("Failed to parse test_action payload, fallback to defaults: {}", rawPayload, exception);
            return TestActionPayload.empty();
        }
    }

    public record TestActionPayload(Integer sleepSeconds, String forceResult, Double successRate) {

        public static TestActionPayload empty() {
            return new TestActionPayload(null, null, null);
        }
    }
}