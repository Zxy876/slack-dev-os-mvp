package com.asyncaiflow.worker.sdk;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.sdk.model.ActionAssignment;

public class WorkerLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerLoop.class);

    private final WorkerConfig config;
    private final AsyncAiFlowWorkerClient client;
    private final WorkerActionHandler actionHandler;
    private final Duration leaseRenewInterval;

    public WorkerLoop(WorkerConfig config, AsyncAiFlowWorkerClient client, WorkerActionHandler actionHandler) {
        this(config, client, actionHandler, Duration.ofSeconds(10));
    }

    WorkerLoop(
            WorkerConfig config,
            AsyncAiFlowWorkerClient client,
            WorkerActionHandler actionHandler,
            Duration leaseRenewInterval) {
        this.config = config;
        this.client = client;
        this.actionHandler = actionHandler;
        this.leaseRenewInterval = normalizeRenewInterval(leaseRenewInterval);
    }

    public void runForever() {
        client.registerWorker(config.workerId(), config.capabilities());
        LOGGER.info("Worker {} registered with capabilities {}", config.workerId(), config.capabilities());

        Instant nextHeartbeatAt = Instant.EPOCH;
        int processedActionCount = 0;
        while (config.maxActions() <= 0 || processedActionCount < config.maxActions()) {
            Instant now = Instant.now();
            if (!now.isBefore(nextHeartbeatAt)) {
                safeHeartbeat();
                nextHeartbeatAt = now.plus(config.heartbeatInterval());
            }

            Optional<ActionAssignment> maybeAssignment = safePoll();
            if (maybeAssignment.isEmpty()) {
                sleep(config.pollInterval());
                continue;
            }

            ActionAssignment assignment = maybeAssignment.get();
            LOGGER.info("Worker {} claimed action {} type={} leaseExpireAt={}",
                    config.workerId(), assignment.actionId(), assignment.type(), assignment.leaseExpireAt());

            LeaseRenewer leaseRenewer = startLeaseRenewer(assignment);
            WorkerExecutionResult executionResult;
            try {
                executionResult = actionHandler.execute(assignment);
            } catch (Exception exception) {
                LOGGER.warn("Worker {} failed to execute action {}", config.workerId(), assignment.actionId(), exception);
                executionResult = WorkerExecutionResult.failed("execution exception", exception.getMessage());
            } finally {
                leaseRenewer.stop();
            }

            client.submitResult(
                    config.workerId(),
                    assignment.actionId(),
                    executionResult.status(),
                    executionResult.result(),
                    executionResult.errorMessage()
            );
            processedActionCount++;
        }

        LOGGER.info("Worker {} stopped after processing {} actions", config.workerId(), processedActionCount);
    }

    private void safeHeartbeat() {
        try {
            client.heartbeat(config.workerId());
        } catch (Exception exception) {
            LOGGER.warn("Worker {} heartbeat failed", config.workerId(), exception);
        }
    }

    private Optional<ActionAssignment> safePoll() {
        try {
            return client.pollAction(config.workerId());
        } catch (Exception exception) {
            LOGGER.warn("Worker {} poll failed", config.workerId(), exception);
            sleep(config.pollInterval());
            return Optional.empty();
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1L, duration.toMillis()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerClientException("Worker loop interrupted", exception);
        }
    }

    private LeaseRenewer startLeaseRenewer(ActionAssignment assignment) {
        if (assignment.leaseExpireAt() == null) {
            return LeaseRenewer.NOOP;
        }

        long intervalMillis = Math.max(1L, leaseRenewInterval.toMillis());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "asyncaiflow-lease-renew-" + assignment.actionId());
            thread.setDaemon(true);
            return thread;
        });
        AtomicBoolean active = new AtomicBoolean(true);

        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
            if (!active.get()) {
                return;
            }
            try {
                client.renewLease(config.workerId(), assignment.actionId());
            } catch (Exception exception) {
                LOGGER.warn("Worker {} lease renewal failed for action {}", config.workerId(), assignment.actionId(), exception);
            }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);

        return () -> {
            active.set(false);
            future.cancel(true);
            scheduler.shutdownNow();
        };
    }

    private static Duration normalizeRenewInterval(Duration configured) {
        if (configured == null || configured.isNegative() || configured.isZero()) {
            return Duration.ofSeconds(10);
        }
        return configured;
    }

    private interface LeaseRenewer {

        LeaseRenewer NOOP = () -> {
        };

        void stop();
    }
}