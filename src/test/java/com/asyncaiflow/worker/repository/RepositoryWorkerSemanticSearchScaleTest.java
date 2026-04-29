package com.asyncaiflow.worker.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class RepositoryWorkerSemanticSearchScaleTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspaceRoot;

    @Test
    void searchSemanticRemainsStableWithLargeRepository() throws Exception {
        Path repoRoot = workspaceRoot.resolve("large-repo");
        Path generatedRoot = repoRoot.resolve("src/generated");
        Files.createDirectories(generatedRoot);

        for (int index = 0; index < 1200; index++) {
            Path generatedFile = generatedRoot.resolve("Generated" + index + ".java");
            String content = "class Generated" + index + " {\n  String value() { return \"filler-" + index + "\"; }\n}\n";
            Files.writeString(generatedFile, content, StandardCharsets.UTF_8);
        }

        Path targetFile = repoRoot.resolve("src/runtime/SceneRuntime.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, """
                class SceneRuntime {
                  String semanticAnchor() {
                    return "scene runtime semantic anchor todo fix";
                  }
                }
                """, StandardCharsets.UTF_8);

        RepositoryWorkerActionHandler handler = newHandler();
        String payload = OBJECT_MAPPER.writeValueAsString(Map.of(
                "schemaVersion", "v1",
                "query", "scene runtime semantic anchor todo fix",
                "topK", 5,
                "scope", Map.of("paths", List.of("large-repo/src"))));

        Instant startedAt = Instant.now();
        WorkerExecutionResult result = handler.execute(action("search_semantic", payload));
        long elapsedMillis = Duration.between(startedAt, Instant.now()).toMillis();

        assertEquals("SUCCEEDED", result.status(), result.errorMessage());

        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertTrue(resultNode.path("matchCount").asInt() >= 1);
        assertEquals("large-repo/src/runtime/SceneRuntime.java", resultNode.path("matches").get(0).path("path").asText());
        assertTrue(elapsedMillis < 15000, "semantic search should remain stable for 1000+ files");
    }

    private RepositoryWorkerActionHandler newHandler() {
        ActionSchemaResolver resolver = new ActionSchemaResolver("schemas/v1");
        ActionSchemaValidator validator = new ActionSchemaValidator(OBJECT_MAPPER, resolver);
        return new RepositoryWorkerActionHandler(
                OBJECT_MAPPER,
                workspaceRoot,
                validator,
                SchemaValidationMode.STRICT,
                20,
                65536,
                List.of(".git", ".idea", ".aiflow", "target", "build", "node_modules"),
                5,
                3,
                4000,
                null);
    }

    private static ActionAssignment action(String type, String payload) {
        return new ActionAssignment(901L, 41L, type, payload, 0, LocalDateTime.now().plusMinutes(2));
    }
}