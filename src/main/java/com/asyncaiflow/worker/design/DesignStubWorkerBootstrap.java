package com.asyncaiflow.worker.design;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.asyncaiflow.design.DesignWorkflowConstants;
import com.asyncaiflow.worker.sdk.AsyncAiFlowWorkerClient;
import com.asyncaiflow.worker.sdk.WorkerConfig;
import com.asyncaiflow.worker.sdk.WorkerLoop;

import jakarta.annotation.PreDestroy;

@Component
public class DesignStubWorkerBootstrap {

    private static final Logger LOGGER = LoggerFactory.getLogger(DesignStubWorkerBootstrap.class);

    private final TranslatorStubWorker translatorStubWorker;
    private final RendererStubWorker rendererStubWorker;
    private final ExecutorService executorService = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    @Value("${clothes-change.stub-workers.enabled:true}")
    private boolean enabled;

    /**
     * When false (default), the TranslatorStub loop is not started because the external
     * Python GPT worker (design-gpt-worker-py) handles nl_to_design_dsl actions.
     * Set to true only when running without an OpenAI API key for local stub testing.
     */
    @Value("${clothes-change.stub-workers.translator-stub-enabled:false}")
    private boolean translatorStubEnabled;

    /**
     * When false (default), renderer actions are handled by external Python assembly worker.
     * Set to true only for local fallback tests that rely on RendererStubWorker.
     */
    @Value("${clothes-change.stub-workers.renderer-stub-enabled:false}")
    private boolean rendererStubEnabled;

    @Value("${clothes-change.stub-workers.server-base-url:http://localhost:${server.port:8080}}")
    private String serverBaseUrl;

    @Value("${clothes-change.stub-workers.poll-interval-ms:500}")
    private long pollIntervalMs;

    @Value("${clothes-change.stub-workers.heartbeat-interval-ms:1000}")
    private long heartbeatIntervalMs;

    public DesignStubWorkerBootstrap(
            TranslatorStubWorker translatorStubWorker,
            RendererStubWorker rendererStubWorker
    ) {
        this.translatorStubWorker = translatorStubWorker;
        this.rendererStubWorker = rendererStubWorker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) {
            LOGGER.info("Design stub workers are disabled");
            return;
        }

        if (translatorStubEnabled) {
            executorService.submit(() -> runLoop(
                    DesignWorkflowConstants.TRANSLATOR_WORKER_ID,
                    List.of(DesignWorkflowConstants.TRANSLATOR_ACTION_TYPE),
                    translatorStubWorker
            ));
            LOGGER.info("TranslatorStubWorker started (translator-stub-enabled=true)");
        } else {
            LOGGER.info("TranslatorStubWorker skipped — Python GPT worker (design-gpt-worker-py) handles nl_to_design_dsl");
        }

        if (rendererStubEnabled) {
            executorService.submit(() -> runLoop(
                DesignWorkflowConstants.RENDERER_WORKER_ID,
                List.of(DesignWorkflowConstants.RENDERER_ACTION_TYPE),
                rendererStubWorker
            ));
            LOGGER.info("RendererStubWorker started (renderer-stub-enabled=true)");
        } else {
            LOGGER.info("RendererStubWorker skipped — external assembly worker handles {}",
                DesignWorkflowConstants.RENDERER_ACTION_TYPE);
        }
    }

    @PreDestroy
    public void stop() {
        executorService.shutdownNow();
    }

    private void runLoop(String workerId, List<String> capabilities, com.asyncaiflow.worker.sdk.WorkerActionHandler actionHandler) {
        WorkerConfig config = new WorkerConfig(
                serverBaseUrl,
                workerId,
                capabilities,
                Duration.ofMillis(Math.max(200L, pollIntervalMs)),
                Duration.ofMillis(Math.max(1000L, heartbeatIntervalMs)),
                0
        );
        LOGGER.info("Starting internal stub worker {} with capabilities {} against {}",
                workerId,
                capabilities,
                serverBaseUrl);
        WorkerLoop workerLoop = new WorkerLoop(config, new AsyncAiFlowWorkerClient(serverBaseUrl), actionHandler);
        workerLoop.runForever();
    }
}