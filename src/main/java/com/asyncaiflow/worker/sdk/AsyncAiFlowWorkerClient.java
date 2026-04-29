package com.asyncaiflow.worker.sdk;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.asyncaiflow.worker.sdk.model.ActionSnapshot;
import com.asyncaiflow.worker.sdk.model.ApiEnvelope;
import com.asyncaiflow.worker.sdk.model.RenewActionLeaseRequest;
import com.asyncaiflow.worker.sdk.model.SubmitActionResultRequest;
import com.asyncaiflow.worker.sdk.model.WorkerHeartbeatRequest;
import com.asyncaiflow.worker.sdk.model.WorkerRegistrationRequest;
import com.asyncaiflow.worker.sdk.model.WorkerSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

public class AsyncAiFlowWorkerClient {

    private static final TypeReference<ApiEnvelope<WorkerSnapshot>> WORKER_RESPONSE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ApiEnvelope<ActionAssignment>> ACTION_ASSIGNMENT_RESPONSE_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<ApiEnvelope<ActionSnapshot>> ACTION_SNAPSHOT_RESPONSE_TYPE = new TypeReference<>() {
    };

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AsyncAiFlowWorkerClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), defaultObjectMapper());
    }

    public AsyncAiFlowWorkerClient(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public WorkerSnapshot registerWorker(String workerId, List<String> capabilities) {
        return sendJson(
                "/worker/register",
                "POST",
                new WorkerRegistrationRequest(workerId, capabilities),
                WORKER_RESPONSE_TYPE
        );
    }

    public WorkerSnapshot heartbeat(String workerId) {
        return sendJson(
                "/worker/heartbeat",
                "POST",
                new WorkerHeartbeatRequest(workerId),
                WORKER_RESPONSE_TYPE
        );
    }

    public Optional<ActionAssignment> pollAction(String workerId) {
        HttpRequest request = HttpRequest.newBuilder(buildUri(
                        "/action/poll?workerId=" + URLEncoder.encode(workerId, StandardCharsets.UTF_8)))
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        if (response.statusCode() == 204 || response.body() == null || response.body().isBlank()) {
            return Optional.empty();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WorkerClientException("Poll request failed with status " + response.statusCode() + ": " + response.body());
        }
        return Optional.of(readEnvelope(response.body(), ACTION_ASSIGNMENT_RESPONSE_TYPE));
    }

    public ActionSnapshot submitResult(String workerId, Long actionId, String status, String result, String errorMessage) {
        return sendJson(
                "/action/result",
                "POST",
                new SubmitActionResultRequest(workerId, actionId, status, result, errorMessage),
                ACTION_SNAPSHOT_RESPONSE_TYPE
        );
    }

    public ActionSnapshot renewLease(String workerId, Long actionId) {
        return sendJson(
                "/action/" + actionId + "/renew-lease",
                "POST",
                new RenewActionLeaseRequest(workerId),
                ACTION_SNAPSHOT_RESPONSE_TYPE
        );
    }

    private <T> T sendJson(String path, String method, Object payload, TypeReference<ApiEnvelope<T>> responseType) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(buildUri(path))
                .header("Content-Type", "application/json");
        String rawBody = writeJson(payload);
        HttpRequest request = switch (method) {
            case "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(rawBody)).build();
            default -> throw new WorkerClientException("Unsupported HTTP method: " + method);
        };

        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WorkerClientException("Request failed with status " + response.statusCode() + ": " + response.body());
        }
        return readEnvelope(response.body(), responseType);
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new WorkerClientException("I/O error while calling AsyncAIFlow server", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkerClientException("HTTP request interrupted", exception);
        }
    }

    private <T> T readEnvelope(String body, TypeReference<ApiEnvelope<T>> responseType) {
        try {
            ApiEnvelope<T> envelope = objectMapper.readValue(body, responseType);
            if (!envelope.success()) {
                throw new WorkerClientException("AsyncAIFlow server returned failure: " + envelope.message());
            }
            return envelope.data();
        } catch (JsonProcessingException exception) {
            throw new WorkerClientException("Failed to parse AsyncAIFlow response body", exception);
        }
    }

    private String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new WorkerClientException("Failed to serialize worker request", exception);
        }
    }

    private URI buildUri(String path) {
        return URI.create(baseUrl + path);
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new WorkerClientException("serverBaseUrl must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}