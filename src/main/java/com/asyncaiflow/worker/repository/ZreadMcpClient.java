package com.asyncaiflow.worker.repository;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ZreadMcpClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZreadMcpClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String authorizationHeader;
    private final Duration timeout;
    private final List<String> toolNames;

    public ZreadMcpClient(
            ObjectMapper objectMapper,
            String endpoint,
            String authorizationHeader,
            Duration timeout,
            List<String> toolNames) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout == null ? Duration.ofSeconds(10) : timeout)
                .build();
        this.endpoint = parseEndpoint(endpoint);
        this.authorizationHeader = normalizeAuthorizationHeader(authorizationHeader);
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.toolNames = sanitizeToolNames(toolNames);
    }

    public boolean enabled() {
        return endpoint != null && !toolNames.isEmpty();
    }

    public Optional<SearchResult> search(String query, int topK, List<String> scopePaths, String repoPath) {
        if (!enabled()) {
            return Optional.empty();
        }

        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isBlank()) {
            return Optional.empty();
        }

        for (String toolName : toolNames) {
            try {
                Optional<SearchResult> result = callSearchTool(toolName, normalizedQuery, topK, scopePaths, repoPath);
                if (result.isPresent() && !result.get().matches().isEmpty()) {
                    return result;
                }
            } catch (RuntimeException | IOException | InterruptedException exception) {
                LOGGER.warn("zread_mcp_search_failed endpoint={} tool={} reason={}",
                        endpoint, toolName, exception.getMessage());
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    private Optional<SearchResult> callSearchTool(
            String toolName,
            String query,
            int topK,
            List<String> scopePaths,
            String repoPath) throws IOException, InterruptedException {
        ObjectNode requestNode = objectMapper.createObjectNode();
        requestNode.put("jsonrpc", "2.0");
        requestNode.put("id", "asyncaiflow-" + System.nanoTime());
        requestNode.put("method", "tools/call");

        ObjectNode params = requestNode.putObject("params");
        params.put("name", toolName);

        ObjectNode arguments = params.putObject("arguments");
        arguments.put("query", query);
        arguments.put("top_k", topK);
        arguments.put("topK", topK);
        arguments.put("limit", topK);

        if (repoPath != null && !repoPath.isBlank()) {
            arguments.put("repo_path", repoPath);
            arguments.put("repoPath", repoPath);
        }

        if (scopePaths != null && !scopePaths.isEmpty()) {
            ArrayNode paths = arguments.putArray("paths");
            for (String scopePath : scopePaths) {
                if (scopePath != null && !scopePath.isBlank()) {
                    paths.add(scopePath);
                }
            }
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestNode)));

        if (!authorizationHeader.isBlank()) {
            requestBuilder.header("Authorization", authorizationHeader);
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from zread mcp: "
                    + summarize(response.body(), 512));
        }

        JsonNode responseNode = parseTransportResponse(response.body());
        if (responseNode.has("error")) {
            throw new IOException("zread mcp returned error: " + responseNode.path("error").toString());
        }

        JsonNode resultNode = responseNode.path("result");
        if (resultNode.isMissingNode() || resultNode.isNull()) {
            return Optional.empty();
        }

        List<Match> extractedMatches = extractMatches(resultNode, toolName, topK);
        return Optional.of(new SearchResult(toolName, extractedMatches));
    }

    private JsonNode parseTransportResponse(String body) throws IOException {
        String normalized = body == null ? "" : body.trim();
        if (normalized.isBlank()) {
            throw new IOException("zread mcp returned empty response body");
        }

        if (normalized.startsWith("{") || normalized.startsWith("[")) {
            return objectMapper.readTree(normalized);
        }

        Optional<JsonNode> ssePayload = extractFirstSseJsonPayload(normalized);
        if (ssePayload.isPresent()) {
            return ssePayload.get();
        }

        throw new IOException("zread mcp returned unsupported payload format: " + summarize(normalized, 512));
    }

    private Optional<JsonNode> extractFirstSseJsonPayload(String payload) {
        for (String line : payload.split("\\R")) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.startsWith("data:")) {
                continue;
            }

            String data = trimmed.substring("data:".length()).trim();
            if (data.isBlank() || "[DONE]".equals(data)) {
                continue;
            }

            try {
                return Optional.of(objectMapper.readTree(data));
            } catch (IOException ignored) {
                // Continue scanning other data lines in case the payload spans multiple events.
            }
        }
        return Optional.empty();
    }

    private List<Match> extractMatches(JsonNode resultNode, String toolName, int topK) {
        List<Match> matches = new ArrayList<>();
        collectMatches(resultNode, toolName, matches, 0);

        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        ArrayList<Match> deduplicated = new ArrayList<>();
        for (Match match : matches) {
            if (match.path().isBlank() && match.chunk().isBlank()) {
                continue;
            }

            String dedupeKey = (match.path() + "|" + match.chunk() + "|" + match.lineNumber()).toLowerCase(Locale.ROOT);
            if (!seenKeys.add(dedupeKey)) {
                continue;
            }
            deduplicated.add(match);
        }

        deduplicated.sort((left, right) -> {
            int scoreCompare = Double.compare(right.score(), left.score());
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            return left.path().compareTo(right.path());
        });

        if (deduplicated.size() <= topK) {
            return List.copyOf(deduplicated);
        }
        return List.copyOf(deduplicated.subList(0, topK));
    }

    private void collectMatches(JsonNode node, String toolName, List<Match> matches, int depth) {
        if (node == null || node.isMissingNode() || node.isNull() || depth > 5) {
            return;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                collectMatches(item, toolName, matches, depth + 1);
            }
            return;
        }

        if (!node.isObject()) {
            if (node.isTextual()) {
                parseEmbeddedJson(node.asText(""))
                        .ifPresent(parsed -> collectMatches(parsed, toolName, matches, depth + 1));
            }
            return;
        }

        toMatch(node, toolName).ifPresent(matches::add);

        for (String key : List.of("matches", "results", "items", "documents", "chunks", "hits", "data", "structuredContent")) {
            JsonNode child = node.get(key);
            if (child != null) {
                collectMatches(child, toolName, matches, depth + 1);
            }
        }

        JsonNode content = node.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode contentNode : content) {
                collectMatches(contentNode, toolName, matches, depth + 1);
                if (contentNode.isObject()) {
                    parseEmbeddedJson(contentNode.path("text").asText(""))
                            .ifPresent(parsed -> collectMatches(parsed, toolName, matches, depth + 1));
                }
                if (contentNode.isTextual()) {
                    parseEmbeddedJson(contentNode.asText(""))
                            .ifPresent(parsed -> collectMatches(parsed, toolName, matches, depth + 1));
                }
            }
        }
    }

    private Optional<Match> toMatch(JsonNode node, String toolName) {
        String path = firstText(node, "file", "path", "file_path", "filePath", "source", "uri", "document");
        String chunk = firstText(node, "chunk", "snippet", "text", "content", "summary", "lineText");
        double score = firstDouble(node, "score", "similarity", "relevance").orElse(0.0D);
        Integer lineNumber = firstInteger(node, "line", "lineNumber", "startLine").orElse(null);

        if (path.isBlank() && chunk.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new Match(path, score, chunk, lineNumber, toolName));
    }

    private Optional<JsonNode> parseEmbeddedJson(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }

        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return Optional.empty();
        }

        try {
            return Optional.of(objectMapper.readTree(trimmed));
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private Optional<Double> firstDouble(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isNumber()) {
                return Optional.of(valueNode.asDouble());
            }
            if (valueNode.isTextual()) {
                try {
                    return Optional.of(Double.parseDouble(valueNode.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed score values from upstream tool payload.
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> firstInteger(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode == null || valueNode.isNull()) {
                continue;
            }
            if (valueNode.isInt() || valueNode.isLong()) {
                return Optional.of(valueNode.asInt());
            }
            if (valueNode.isTextual()) {
                try {
                    return Optional.of(Integer.parseInt(valueNode.asText().trim()));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed line number values from upstream tool payload.
                }
            }
        }
        return Optional.empty();
    }

    private String firstText(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode valueNode = node.get(fieldName);
            if (valueNode != null && valueNode.isTextual() && !valueNode.asText().isBlank()) {
                return valueNode.asText();
            }
        }
        return "";
    }

    private static List<String> sanitizeToolNames(List<String> toolNames) {
        if (toolNames == null) {
            return List.of();
        }

        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String toolName : toolNames) {
            if (toolName != null && !toolName.isBlank()) {
                sanitized.add(toolName.trim());
            }
        }
        return List.copyOf(sanitized);
    }

    private static URI parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }

        try {
            return URI.create(endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Zread MCP endpoint: " + endpoint, exception);
        }
    }

    private static String normalizeAuthorizationHeader(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bearer ") || lower.startsWith("basic ")) {
            return trimmed;
        }
        return "Bearer " + trimmed;
    }

    private static String summarize(String value, int maxLength) {
        if (value == null) {
            return "";
        }

        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    public record Match(String path, double score, String chunk, Integer lineNumber, String source) {
    }

    public record SearchResult(String toolName, List<Match> matches) {
    }
}
