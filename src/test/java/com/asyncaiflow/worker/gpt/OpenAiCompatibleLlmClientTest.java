package com.asyncaiflow.worker.gpt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class OpenAiCompatibleLlmClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void versionedBaseUrlDoesNotDuplicateVersionedEndpoint() throws Exception {
        RequestCapture capture = executeCompletion(
                "/api/coding/paas/v4",
                "/v1/chat/completions",
                """
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "ok"
                              }
                            }
                          ]
                        }
                        """);

        assertEquals("ok", capture.completion());
        assertEquals("/api/coding/paas/v4/chat/completions", capture.requestPath());
    }

    @Test
    void unversionedBaseUrlKeepsEndpointVersionSegment() throws Exception {
        RequestCapture capture = executeCompletion(
                "",
                "/v1/chat/completions",
                """
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "ok"
                              }
                            }
                          ]
                        }
                        """);

        assertEquals("ok", capture.completion());
        assertEquals("/v1/chat/completions", capture.requestPath());
    }

    @Test
    void blankContentFallsBackToReasoningContent() throws Exception {
        RequestCapture capture = executeCompletion(
                "/api/coding/paas/v4",
                "/v1/chat/completions",
                """
                        {
                          "choices": [
                            {
                              "message": {
                                "content": "",
                                "reasoning_content": "reasoning fallback"
                              }
                            }
                          ]
                        }
                        """);

        assertEquals("reasoning fallback", capture.completion());
        assertEquals("/api/coding/paas/v4/chat/completions", capture.requestPath());
    }

    private RequestCapture executeCompletion(String basePath, String endpoint, String responseBody) throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> respond(exchange, requestPath, responseBody));
        server.start();

        try {
            GptWorkerProperties.LlmProperties properties = new GptWorkerProperties.LlmProperties();
            properties.setApiKey("test-key");
            properties.setModel("test-model");
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + basePath);
            properties.setEndpoint(endpoint);

            OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(OBJECT_MAPPER, properties);
            String completion = client.complete("system", "user");
            return new RequestCapture(requestPath.get(), completion);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> requestPath, String responseBody)
            throws IOException {
        try (exchange) {
            requestPath.set(exchange.getRequestURI().getPath());

            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private record RequestCapture(String requestPath, String completion) {
    }
}