package com.asyncaiflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SchedulerMaintenanceService {

    private final ActionService actionService;
    private final WorkerService workerService;

    @Value("${asyncaiflow.worker.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;

    public SchedulerMaintenanceService(ActionService actionService, WorkerService workerService) {
        this.actionService = actionService;
        this.workerService = workerService;
    }

    @Scheduled(fixedDelayString = "${asyncaiflow.scheduler.action-scan-fixed-delay-ms:2000}")
    public void maintainActions() {
        actionService.reclaimExpiredLeases();
        actionService.enqueueDueRetries();
        actionService.dispatchAllRunnableActions();
    }

    @Scheduled(fixedDelayString = "${asyncaiflow.scheduler.worker-scan-fixed-delay-ms:10000}")
    public void maintainWorkers() {
        workerService.markStaleWorkers(heartbeatTimeoutSeconds);
    }
}
