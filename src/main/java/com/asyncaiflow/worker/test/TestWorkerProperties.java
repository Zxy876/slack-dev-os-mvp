package com.asyncaiflow.worker.test;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "asyncaiflow.reference-worker")
public class TestWorkerProperties {

    private String serverBaseUrl = "http://localhost:8080";

    private String workerId = "test-worker-1";

    private List<String> capabilities = new ArrayList<>(List.of("test_action"));

    private long pollIntervalMillis = 2000L;

    private long heartbeatIntervalMillis = 30000L;

    private int maxActions = 0;

    private TestActionProperties test = new TestActionProperties();

    public String getServerBaseUrl() {
        return serverBaseUrl;
    }

    public void setServerBaseUrl(String serverBaseUrl) {
        this.serverBaseUrl = serverBaseUrl;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public long getHeartbeatIntervalMillis() {
        return heartbeatIntervalMillis;
    }

    public void setHeartbeatIntervalMillis(long heartbeatIntervalMillis) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    public int getMaxActions() {
        return maxActions;
    }

    public void setMaxActions(int maxActions) {
        this.maxActions = maxActions;
    }

    public TestActionProperties getTest() {
        return test;
    }

    public void setTest(TestActionProperties test) {
        this.test = test;
    }

    public static class TestActionProperties {

        private int minSleepSeconds = 1;

        private int maxSleepSeconds = 5;

        private double successRate = 0.7D;

        public int getMinSleepSeconds() {
            return minSleepSeconds;
        }

        public void setMinSleepSeconds(int minSleepSeconds) {
            this.minSleepSeconds = minSleepSeconds;
        }

        public int getMaxSleepSeconds() {
            return maxSleepSeconds;
        }

        public void setMaxSleepSeconds(int maxSleepSeconds) {
            this.maxSleepSeconds = maxSleepSeconds;
        }

        public double getSuccessRate() {
            return successRate;
        }

        public void setSuccessRate(double successRate) {
            this.successRate = successRate;
        }
    }
}