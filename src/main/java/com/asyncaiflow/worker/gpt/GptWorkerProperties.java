package com.asyncaiflow.worker.gpt;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;

@ConfigurationProperties(prefix = "asyncaiflow.gpt-worker")
public class GptWorkerProperties {

    private String serverBaseUrl = "http://localhost:8080";

    private String workerId = "gpt-worker-1";

    private List<String> capabilities = new ArrayList<>(List.of(
            "design_solution",
            "review_code",
            "generate_explanation",
            "generate_patch",
            "review_patch"
    ));

    private long pollIntervalMillis = 2000L;

    private long heartbeatIntervalMillis = 10000L;

    private int maxActions = 0;

    private LlmProperties llm = new LlmProperties();

    private ValidationProperties validation = new ValidationProperties();

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

    public LlmProperties getLlm() {
        return llm;
    }

    public void setLlm(LlmProperties llm) {
        this.llm = llm;
    }

    public ValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(ValidationProperties validation) {
        this.validation = validation;
    }

    public static class ValidationProperties {

        private SchemaValidationMode mode = SchemaValidationMode.WARN;

        private String schemaBasePath = "schemas/v1";

        public SchemaValidationMode getMode() {
            return mode;
        }

        public void setMode(SchemaValidationMode mode) {
            this.mode = mode;
        }

        public String getSchemaBasePath() {
            return schemaBasePath;
        }

        public void setSchemaBasePath(String schemaBasePath) {
            this.schemaBasePath = schemaBasePath;
        }
    }

    public static class LlmProperties {

        private String baseUrl = "https://api.openai.com";

        private String endpoint = "/v1/chat/completions";

        private String apiKey = "";

        private String model = "gpt-4.1-mini";

        private double temperature = 0.2D;

        private int maxOutputTokens = 1200;

        private long connectTimeoutMillis = 5000L;

        private long readTimeoutMillis = 60000L;

        private boolean mockFallbackEnabled = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public long getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public long getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(long readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }

        public boolean isMockFallbackEnabled() {
            return mockFallbackEnabled;
        }

        public void setMockFallbackEnabled(boolean mockFallbackEnabled) {
            this.mockFallbackEnabled = mockFallbackEnabled;
        }
    }
}
