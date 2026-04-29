package com.asyncaiflow.worker.git;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;

@ConfigurationProperties(prefix = "asyncaiflow.git-worker")
public class GitWorkerProperties {

    private String serverBaseUrl = "http://localhost:8080";

    private String workerId = "git-worker-1";

    private List<String> capabilities = new ArrayList<>(List.of(
            "create_branch",
            "apply_patch",
            "commit_changes"
    ));

    private long pollIntervalMillis = 2000L;

    private long heartbeatIntervalMillis = 10000L;

    private int maxActions = 0;

    private ValidationProperties validation = new ValidationProperties();

    private RepositoryProperties repository = new RepositoryProperties();

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
        this.capabilities = capabilities == null ? new ArrayList<>() : new ArrayList<>(capabilities);
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

    public ValidationProperties getValidation() {
        return validation;
    }

    public void setValidation(ValidationProperties validation) {
        this.validation = validation;
    }

    public RepositoryProperties getRepository() {
        return repository;
    }

    public void setRepository(RepositoryProperties repository) {
        this.repository = repository;
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

    public static class RepositoryProperties {

        private String workspaceRoot = ".";

        private int maxPatchBytes = 262144;

        private int maxCommandOutputBytes = 65536;

        private long commandTimeoutMillis = 20000L;

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        public int getMaxPatchBytes() {
            return maxPatchBytes;
        }

        public void setMaxPatchBytes(int maxPatchBytes) {
            this.maxPatchBytes = maxPatchBytes;
        }

        public int getMaxCommandOutputBytes() {
            return maxCommandOutputBytes;
        }

        public void setMaxCommandOutputBytes(int maxCommandOutputBytes) {
            this.maxCommandOutputBytes = maxCommandOutputBytes;
        }

        public long getCommandTimeoutMillis() {
            return commandTimeoutMillis;
        }

        public void setCommandTimeoutMillis(long commandTimeoutMillis) {
            this.commandTimeoutMillis = commandTimeoutMillis;
        }
    }
}
