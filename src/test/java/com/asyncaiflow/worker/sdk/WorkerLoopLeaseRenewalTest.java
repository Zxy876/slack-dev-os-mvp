package com.asyncaiflow.worker.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.asyncaiflow.worker.sdk.model.ActionSnapshot;
import com.asyncaiflow.worker.sdk.model.WorkerSnapshot;

class WorkerLoopLeaseRenewalTest {

    @Test
    void renewLeaseRunsDuringLongExecution() {
        FakeClient client = new FakeClient(actionWithLease(LocalDateTime.now().plusMinutes(2)));
        WorkerConfig config = new WorkerConfig(
                "http://localhost:8080",
                "test-worker",
                List.of("design_solution"),
                Duration.ofMillis(20),
                Duration.ofSeconds(30),
                1
        );

        WorkerLoop loop = new WorkerLoop(
                config,
                client,
                assignment -> {
                    Thread.sleep(150L);
                    return WorkerExecutionResult.succeeded("ok");
                },
                Duration.ofMillis(25)
        );

        loop.runForever();

        assertTrue(client.renewCalls() >= 1);
        assertEquals(1, client.submitCalls());
    }

    @Test
    void renewLeaseSkippedWhenAssignmentHasNoLeaseDeadline() {
        FakeClient client = new FakeClient(actionWithLease(null));
        WorkerConfig config = new WorkerConfig(
                "http://localhost:8080",
                "test-worker",
                List.of("design_solution"),
                Duration.ofMillis(20),
                Duration.ofSeconds(30),
                1
        );

        WorkerLoop loop = new WorkerLoop(
                config,
                client,
                assignment -> {
                    Thread.sleep(120L);
                    return WorkerExecutionResult.succeeded("ok");
                },
                Duration.ofMillis(20)
        );

        loop.runForever();

        assertEquals(0, client.renewCalls());
        assertEquals(1, client.submitCalls());
    }

    private static ActionAssignment actionWithLease(LocalDateTime leaseExpireAt) {
        return new ActionAssignment(
                101L,
                11L,
                "design_solution",
                "{\"schemaVersion\":\"v1\",\"issue\":\"x\"}",
                0,
                leaseExpireAt
        );
    }

    private static final class FakeClient extends AsyncAiFlowWorkerClient {

        private final AtomicReference<ActionAssignment> assignmentRef;
        private final AtomicInteger renewCalls = new AtomicInteger();
        private final AtomicInteger submitCalls = new AtomicInteger();

        private FakeClient(ActionAssignment assignment) {
            super("http://localhost:8080");
            this.assignmentRef = new AtomicReference<>(assignment);
        }

        @Override
        public WorkerSnapshot registerWorker(String workerId, List<String> capabilities) {
            return new WorkerSnapshot(workerId, capabilities, "ONLINE", LocalDateTime.now());
        }

        @Override
        public WorkerSnapshot heartbeat(String workerId) {
            return new WorkerSnapshot(workerId, List.of("design_solution"), "ONLINE", LocalDateTime.now());
        }

        @Override
        public Optional<ActionAssignment> pollAction(String workerId) {
            return Optional.ofNullable(assignmentRef.getAndSet(null));
        }

        @Override
        public ActionSnapshot submitResult(String workerId, Long actionId, String status, String result, String errorMessage) {
            submitCalls.incrementAndGet();
            return snapshot(actionId, workerId, status);
        }

        @Override
        public ActionSnapshot renewLease(String workerId, Long actionId) {
            renewCalls.incrementAndGet();
            return snapshot(actionId, workerId, "RUNNING");
        }

        int renewCalls() {
            return renewCalls.get();
        }

        int submitCalls() {
            return submitCalls.get();
        }

        private ActionSnapshot snapshot(Long actionId, String workerId, String status) {
            LocalDateTime now = LocalDateTime.now();
            return new ActionSnapshot(
                    actionId,
                    11L,
                    "design_solution",
                    status,
                    workerId,
                    0,
                    3,
                    5,
                    300,
                    now.plusMinutes(1),
                    null,
                    now.minusSeconds(2),
                    now.minusSeconds(1),
                    now,
                    null,
                    null,
                    1,
                    0,
                    now,
                    now.minusSeconds(1),
                    1000L,
                    null,
                    "{}",
                    null,
                    List.of(),
                    now,
                    now
            );
        }
    }
}
