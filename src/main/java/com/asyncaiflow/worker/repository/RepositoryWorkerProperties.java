package com.asyncaiflow.worker.repository;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;

@ConfigurationProperties(prefix = "asyncaiflow.repository-worker")
public class RepositoryWorkerProperties {

    private String serverBaseUrl = "http://localhost:8080";

    private String workerId = "repository-worker-1";

    private List<String> capabilities = new ArrayList<>(List.of(
            "search_code",
            "read_file",
            "load_code",
            "search_semantic",
            "build_context_pack"
    ));

    private long pollIntervalMillis = 2000L;

    private long heartbeatIntervalMillis = 10000L;

    private int maxActions = 0;

    private ValidationProperties validation = new ValidationProperties();

    private RepositoryProperties repository = new RepositoryProperties();

    private SemanticProperties semantic = new SemanticProperties();

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

    public SemanticProperties getSemantic() {
        return semantic;
    }

    public void setSemantic(SemanticProperties semantic) {
        this.semantic = semantic;
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

        private int maxSearchResults = 40;

        private int maxReadBytes = 65536;

        private List<String> ignoredDirectories = new ArrayList<>(List.of(
                ".git",
                ".idea",
                ".aiflow",
                "target",
                "build",
                "node_modules"
        ));

        public String getWorkspaceRoot() {
            return workspaceRoot;
        }

        public void setWorkspaceRoot(String workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
        }

        public int getMaxSearchResults() {
            return maxSearchResults;
        }

        public void setMaxSearchResults(int maxSearchResults) {
            this.maxSearchResults = maxSearchResults;
        }

        public int getMaxReadBytes() {
            return maxReadBytes;
        }

        public void setMaxReadBytes(int maxReadBytes) {
            this.maxReadBytes = maxReadBytes;
        }

        public List<String> getIgnoredDirectories() {
            return ignoredDirectories;
        }

        public void setIgnoredDirectories(List<String> ignoredDirectories) {
            this.ignoredDirectories = ignoredDirectories == null ? new ArrayList<>() : new ArrayList<>(ignoredDirectories);
        }
    }

    public static class SemanticProperties {

        private int defaultTopK = 5;

        private int maxContextFiles = 3;

        private int maxCharsPerFile = 4000;

        private ZreadProperties zread = new ZreadProperties();

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public int getMaxContextFiles() {
            return maxContextFiles;
        }

        public void setMaxContextFiles(int maxContextFiles) {
            this.maxContextFiles = maxContextFiles;
        }

        public int getMaxCharsPerFile() {
            return maxCharsPerFile;
        }

        public void setMaxCharsPerFile(int maxCharsPerFile) {
            this.maxCharsPerFile = maxCharsPerFile;
        }

        public ZreadProperties getZread() {
            return zread;
        }

        public void setZread(ZreadProperties zread) {
            this.zread = zread;
        }
    }

    public static class ZreadProperties {

        private boolean enabled = false;

        private String endpoint = "";

        private String authorization = "";

        private long timeoutMillis = 15000L;

        private List<String> toolNames = new ArrayList<>(List.of(
                "search_repo_semantic",
                "search_semantic",
                "semantic_search",
                "search"
        ));

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getAuthorization() {
            return authorization;
        }

        public void setAuthorization(String authorization) {
            this.authorization = authorization;
        }

        public long getTimeoutMillis() {
            return timeoutMillis;
        }

        public void setTimeoutMillis(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        public List<String> getToolNames() {
            return toolNames;
        }

        public void setToolNames(List<String> toolNames) {
            this.toolNames = toolNames == null ? new ArrayList<>() : new ArrayList<>(toolNames);
        }
    }
}