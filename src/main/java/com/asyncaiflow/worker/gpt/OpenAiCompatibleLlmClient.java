package com.asyncaiflow.worker.gpt;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.sdk.WorkerClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class OpenAiCompatibleLlmClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final ObjectMapper objectMapper;
    private final GptWorkerProperties.LlmProperties properties;
    private final HttpClient httpClient;

    public OpenAiCompatibleLlmClient(ObjectMapper objectMapper, GptWorkerProperties.LlmProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000L, properties.getConnectTimeoutMillis())))
                .build();
    }

    public String modelName() {
        return properties.getModel();
    }

    public String complete(String systemPrompt, String userPrompt) {
        if (isBlank(properties.getApiKey())) {
            if (properties.isMockFallbackEnabled()) {
                return mockCompletion(userPrompt);
            }
            throw new WorkerClientException("LLM apiKey is blank and mock fallback is disabled");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(buildUri())
                    .timeout(Duration.ofMillis(Math.max(2000L, properties.getReadTimeoutMillis())))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey().trim())
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(systemPrompt, userPrompt)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WorkerClientException(
                        "LLM request failed with status " + response.statusCode() + ": " + response.body());
            }
            return extractContent(response.body());
        } catch (IOException exception) {
            throw new WorkerClientException("LLM request I/O failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerClientException("LLM request interrupted", exception);
        }
    }

    private String buildRequestBody(String systemPrompt, String userPrompt) {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", properties.getModel());
            request.put("temperature", properties.getTemperature());
            request.put("max_tokens", Math.max(1, properties.getMaxOutputTokens()));

            ArrayNode messages = request.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            return objectMapper.writeValueAsString(request);
        } catch (IOException exception) {
            throw new WorkerClientException("Failed to serialize LLM request body", exception);
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode messageNode = root.path("choices").path(0).path("message");
            JsonNode contentNode = messageNode.path("content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                return extractReasoningContent(messageNode, responseBody,
                        "LLM response missing choices[0].message.content");
            }

            if (contentNode.isArray()) {
                StringBuilder builder = new StringBuilder();
                for (JsonNode part : contentNode) {
                    String text = part.path("text").asText("");
                    if (!text.isBlank()) {
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(text);
                    }
                }
                String aggregated = builder.toString().trim();
                if (!aggregated.isBlank()) {
                    return aggregated;
                }
            }

            String content = contentNode.asText("").trim();
            if (content.isBlank()) {
                return extractReasoningContent(messageNode, responseBody, "LLM response content is blank");
            }
            return content;
        } catch (IOException exception) {
            throw new WorkerClientException("Failed to parse LLM response body", exception);
        }
    }

    private String extractReasoningContent(JsonNode messageNode, String responseBody, String defaultMessage) {
        JsonNode reasoningContentNode = messageNode.path("reasoning_content");
        String reasoningContent = extractTextContent(reasoningContentNode);
        if (!reasoningContent.isBlank()) {
            LOGGER.info("LLM response content was blank; using reasoning_content fallback");
            return reasoningContent;
        }

        throw new WorkerClientException(defaultMessage + "; response body: " + summarize(responseBody, 400));
    }

    private String extractTextContent(JsonNode contentNode) {
        if (contentNode == null || contentNode.isMissingNode() || contentNode.isNull()) {
            return "";
        }

        if (contentNode.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode part : contentNode) {
                String text = part.path("text").asText("");
                if (!text.isBlank()) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            }
            return builder.toString().trim();
        }

        return contentNode.asText("").trim();
    }

    private URI buildUri() {
        String baseUrl = properties.getBaseUrl();
        if (isBlank(baseUrl)) {
            throw new WorkerClientException("LLM baseUrl must not be blank");
        }

        String endpoint = properties.getEndpoint();
        if (isBlank(endpoint)) {
            endpoint = "/v1/chat/completions";
        }

        if (isAbsoluteUrl(endpoint)) {
            return URI.create(endpoint.trim());
        }

        String normalizedBaseUrl = trimRightSlash(baseUrl);
        String normalizedEndpoint = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        normalizedEndpoint = normalizeEndpointForVersionedBaseUrl(normalizedBaseUrl, normalizedEndpoint);
        return URI.create(normalizedBaseUrl + normalizedEndpoint);
    }

    private String normalizeEndpointForVersionedBaseUrl(String baseUrl, String endpoint) {
        URI baseUri = URI.create(baseUrl);
        String basePath = trimRightSlash(baseUri.getPath() == null ? "" : baseUri.getPath());
        String baseVersionSegment = lastPathSegment(basePath);
        String endpointVersionSegment = firstPathSegment(endpoint);

        if (!isVersionSegment(baseVersionSegment) || !isVersionSegment(endpointVersionSegment)) {
            return endpoint;
        }

        String normalizedEndpoint = trimFirstPathSegment(endpoint);
        LOGGER.info("LLM endpoint {} normalized to {} because baseUrl {} already contains a version segment",
                endpoint, normalizedEndpoint, baseUrl);
        return normalizedEndpoint;
    }

    private String mockCompletion(String userPrompt) {
        String compactPrompt = userPrompt == null ? "" : userPrompt.replace('\n', ' ').trim();
        if (compactPrompt.length() > 260) {
            compactPrompt = compactPrompt.substring(0, 260) + "...";
        }

        String actionType = extractActionType(userPrompt);
        String resolvedActionType = actionType == null ? "" : actionType;
        LOGGER.info("LLM apiKey is blank, using mock completion fallback");

        // generate_patch and review_patch require a valid unified diff block — supply a minimal mock patch
        if ("generate_patch".equals(resolvedActionType) || "review_patch".equals(resolvedActionType)) {
            return "[MOCK_PATCH] Mock patch generated by gpt-worker (no API key configured).\n\n" +
                    "## 统一补丁\n\n" +
                    "```diff\n" +
                    "--- a/backend/npc_behavior.py\n" +
                    "+++ b/backend/npc_behavior.py\n" +
                    "@@ -2,3 +2,4 @@\n" +
                    " def npc_act(player_id):\n" +
                    "     pass\n" +
                    " \n" +
                    "+    # AI-suggested fix applied (mock)\n" +
                    "```\n\n" +
                    "Action: " + resolvedActionType + "\n" +
                    "Input summary: " + compactPrompt + "\n" +
                    "Approved: true\n" +
                    "Recommendation: set OPENAI_API_KEY to enable real model execution.";
        }

        String marker = switch (resolvedActionType) {
            case "generate_explanation" -> "MOCK_EXPLANATION";
            case "design_solution" -> "MOCK_DESIGN_SOLUTION";
            case "review_code" -> "MOCK_REVIEW_CODE";
            default -> "MOCK_COMPLETION";
        };
        String intro = switch (resolvedActionType) {
            case "generate_explanation" -> "This explanation is generated by gpt-worker mock fallback.";
            case "design_solution" -> "This solution guidance is generated by gpt-worker mock fallback.";
            case "review_code" -> "This review is generated by gpt-worker mock fallback.";
            default -> "This response is generated by gpt-worker mock fallback.";
        };

        return "[" + marker + "]\n" +
                intro + "\n" +
            "Action: " + (resolvedActionType.isBlank() ? "unknown" : resolvedActionType) + "\n" +
                "Input summary: " + compactPrompt + "\n" +
                "Recommendation: set OPENAI_API_KEY to enable real model execution.";
    }

    private String extractActionType(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return "";
        }

        for (String line : userPrompt.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Action:")) {
                return trimmed.substring("Action:".length()).trim();
            }
        }
        return "";
    }

    private static String trimRightSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String lastPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }

        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static String firstPathSegment(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return "";
        }

        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0) {
            return path.substring(1);
        }
        return path.substring(1, secondSlash);
    }

    private static String trimFirstPathSegment(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return path;
        }

        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0) {
            return "/";
        }
        return path.substring(secondSlash);
    }

    private static boolean isAbsoluteUrl(String value) {
        if (value == null) {
            return false;
        }

        String trimmed = value.trim();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    private static boolean isVersionSegment(String value) {
        return value != null && value.matches("v\\d+");
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
