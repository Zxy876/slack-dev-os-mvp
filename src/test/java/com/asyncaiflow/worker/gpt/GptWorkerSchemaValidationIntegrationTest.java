package com.asyncaiflow.worker.gpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaResolver;
import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class GptWorkerSchemaValidationIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void payloadValidAndSchemaValidExecutionContinues() throws Exception {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.WARN, "schemas/v1", "solution text");

        WorkerExecutionResult result = handler.execute(action("design_solution",
                "{\"schemaVersion\":\"v1\",\"issue\":\"design lease renew\"}"));

        assertEquals("SUCCEEDED", result.status());
        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertEquals("v1", resultNode.path("schemaVersion").asText());
        assertTrue(resultNode.path("summary").asText().length() > 0);
    }

    @Test
    void payloadValidButSchemaInvalidWarnModeContinuesWithWarning() {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.WARN, "schemas/v1", "solution text");

        try (LogCapture capture = LogCapture.forClass(GptWorkerActionHandler.class)) {
            WorkerExecutionResult result = handler.execute(action("design_solution",
                    "{\"schemaVersion\":\"v1\"}"));

            assertEquals("SUCCEEDED", result.status());
            assertTrue(capture.hasWarnForPhase("payload"));
        }
    }

    @Test
    void payloadValidButSchemaInvalidStrictModeFails() {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.STRICT, "schemas/v1", "solution text");

        WorkerExecutionResult result = handler.execute(action("design_solution",
                "{\"schemaVersion\":\"v1\"}"));

        assertEquals("FAILED", result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("issue") || result.errorMessage().contains("属性"));
    }

    @Test
    void payloadInvalidJsonFailsImmediately() {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.WARN, "schemas/v1", "solution text");

        WorkerExecutionResult result = handler.execute(action("design_solution", "not-json"));

        assertEquals("FAILED", result.status());
        assertTrue(result.errorMessage().contains("payload JSON parse failed"));
    }

    @Test
    void payloadSchemaValidationOffModeSkipsValidation() {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.OFF, "schemas/v1", "solution text");

        try (LogCapture capture = LogCapture.forClass(GptWorkerActionHandler.class)) {
            WorkerExecutionResult result = handler.execute(action("design_solution",
                    "{\"schemaVersion\":\"v1\"}"));

            assertEquals("SUCCEEDED", result.status());
            assertFalse(capture.hasWarnForPhase("payload"));
        }
    }

    @Test
    void resultValidSchemaSubmitsNormallyForReviewCode() throws Exception {
        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.WARN, "schemas/v1", "review summary");

        WorkerExecutionResult result = handler.execute(action("review_code",
                "{\"schemaVersion\":\"v1\",\"code\":\"class A {}\"}"));

        assertEquals("SUCCEEDED", result.status());
        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertTrue(resultNode.path("findings").isArray());
        assertEquals(1, resultNode.path("findings").size());
    }

    @Test
    void generatePatchReturnsExtractedPatchResult() throws Exception {
        String completion = """
                结论
                需要最小修复 TODO 分支。

                修改文件
                - src/main/java/demo/SceneRuntime.java

                统一补丁
                ```diff
                diff --git a/src/main/java/demo/SceneRuntime.java b/src/main/java/demo/SceneRuntime.java
                --- a/src/main/java/demo/SceneRuntime.java
                +++ b/src/main/java/demo/SceneRuntime.java
                @@ -1,3 +1,3 @@
                 class SceneRuntime {
                -  String next() { return \"TODO\"; }
                +  String next() { return \"scene-ready\"; }
                 }
                ```

                风险
                低。
                """;

        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.STRICT, "schemas/v1", completion);

        WorkerExecutionResult result = handler.execute(action("generate_patch",
                """
                {
                  "schemaVersion":"v1",
                  "issue":"Fix TODO in SceneRuntime",
                  "file":"src/main/java/demo/SceneRuntime.java",
                  "code":"class SceneRuntime { String next() { return \\\"TODO\\\"; } }"
                }
                """));

        assertEquals("SUCCEEDED", result.status(), result.errorMessage());
        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertTrue(resultNode.path("patch").asText().contains("scene-ready"));
        assertEquals("src/main/java/demo/SceneRuntime.java", resultNode.path("targetFiles").get(0).asText());
    }

    @Test
    void reviewPatchReturnsApprovedPatchResult() throws Exception {
        String completion = """
                结论
                补丁可以应用。

                发现
                变更范围最小。

                是否可应用
                可以应用。

                修订补丁
                ```diff
                diff --git a/src/main/java/demo/SceneRuntime.java b/src/main/java/demo/SceneRuntime.java
                --- a/src/main/java/demo/SceneRuntime.java
                +++ b/src/main/java/demo/SceneRuntime.java
                @@ -1,3 +1,3 @@
                 class SceneRuntime {
                -  String next() { return \"TODO\"; }
                +  String next() { return \"scene-ready\"; }
                 }
                ```

                风险
                低。
                """;
    String patch = """
        diff --git a/src/main/java/demo/SceneRuntime.java b/src/main/java/demo/SceneRuntime.java
        --- a/src/main/java/demo/SceneRuntime.java
        +++ b/src/main/java/demo/SceneRuntime.java
        @@ -1,3 +1,3 @@
         class SceneRuntime {
        -  String next() { return \"TODO\"; }
        +  String next() { return \"scene-ready\"; }
         }
        """;

        GptWorkerActionHandler handler = newHandler(SchemaValidationMode.STRICT, "schemas/v1", completion);

        WorkerExecutionResult result = handler.execute(action("review_patch",
        OBJECT_MAPPER.writeValueAsString(java.util.Map.of(
            "schemaVersion", "v1",
            "issue", "Fix TODO in SceneRuntime",
            "patch", patch))));

        assertEquals("SUCCEEDED", result.status(), result.errorMessage());
        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertTrue(resultNode.path("approved").asBoolean());
        assertTrue(resultNode.path("patch").asText().contains("scene-ready"));
        assertEquals(1, resultNode.path("findings").size());
    }

    @Test
    void generateExplanationMockModeReturnsClearlyMarkedStructuredResult() throws Exception {
        GptWorkerProperties.LlmProperties llmProperties = new GptWorkerProperties.LlmProperties();
        llmProperties.setMockFallbackEnabled(true);

        GptWorkerActionHandler handler = newHandler(
                SchemaValidationMode.STRICT,
                "schemas/v1",
                new OpenAiCompatibleLlmClient(OBJECT_MAPPER, llmProperties));

        WorkerExecutionResult result = handler.execute(action("generate_explanation",
                """
                {
                    \"schemaVersion\":\"v1\",
                    \"issue\":\"Explain how Drift story engine interacts with the Minecraft plugin\",
                    \"repo_context\":\"DriftSystem backend story routes and Minecraft plugin integration\",
                    \"file\":\"backend/app/routers/story.py\",
                    \"module\":\"story engine\",
                    \"gathered_context\":{
                        \"plugin_classes\":[\"StoryCreativeManager\",\"IntentRouter2\"],
                        \"backend_routes\":[\"backend/app/routers/story.py\"]
                    }
                }
                """));

        assertEquals("SUCCEEDED", result.status());

        JsonNode resultNode = OBJECT_MAPPER.readTree(result.result());
        assertEquals("v1", resultNode.path("schemaVersion").asText());
        assertEquals("gpt-worker", resultNode.path("worker").asText());
        assertEquals("gpt-4.1-mini", resultNode.path("model").asText());
        assertTrue(resultNode.path("summary").asText().contains("[MOCK_EXPLANATION]"));
        assertTrue(resultNode.path("content").asText().contains("[MOCK_EXPLANATION]"));
        assertTrue(resultNode.path("content").asText().contains("generate_explanation"));
        assertTrue(resultNode.path("confidence").asDouble() > 0D);
    }

    @Test
    void resultSchemaInvalidWarnModeLogsWarningAndContinues() {
        GptWorkerActionHandler handler = newHandler(
                SchemaValidationMode.WARN,
                "schemas-invalid-result/v1",
                "design summary");

        try (LogCapture capture = LogCapture.forClass(GptWorkerActionHandler.class)) {
            WorkerExecutionResult result = handler.execute(action("design_solution",
                    "{\"schemaVersion\":\"v1\",\"issue\":\"x\"}"));

            assertEquals("SUCCEEDED", result.status());
            assertTrue(capture.hasWarnForPhase("result"));
        }
    }

    @Test
    void resultSchemaInvalidStrictModeFailsBeforeSubmit() {
        GptWorkerActionHandler handler = newHandler(
                SchemaValidationMode.STRICT,
                "schemas-invalid-result/v1",
                "design summary");

        WorkerExecutionResult result = handler.execute(action("design_solution",
                "{\"schemaVersion\":\"v1\",\"issue\":\"x\"}"));

        assertEquals("FAILED", result.status());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("mustHaveFieldForTest")
                || result.errorMessage().contains("required"));
    }

    private static GptWorkerActionHandler newHandler(
            SchemaValidationMode mode,
            String schemaBasePath,
            String completion) {
        GptWorkerProperties.LlmProperties llmProperties = new GptWorkerProperties.LlmProperties();
        llmProperties.setMockFallbackEnabled(true);
        OpenAiCompatibleLlmClient llmClient = new StubLlmClient(completion, llmProperties);

        return newHandler(mode, schemaBasePath, llmClient);
    }

    private static GptWorkerActionHandler newHandler(
            SchemaValidationMode mode,
            String schemaBasePath,
            OpenAiCompatibleLlmClient llmClient) {

        ActionSchemaResolver resolver = new ActionSchemaResolver(schemaBasePath);
        ActionSchemaValidator validator = new ActionSchemaValidator(OBJECT_MAPPER, resolver);
        return new GptWorkerActionHandler(OBJECT_MAPPER, llmClient, validator, mode);
    }

    private static ActionAssignment action(String type, String payload) {
        return new ActionAssignment(101L, 11L, type, payload, 0, LocalDateTime.now().plusMinutes(2));
    }

    private static final class StubLlmClient extends OpenAiCompatibleLlmClient {

        private final String completion;

        private StubLlmClient(String completion, GptWorkerProperties.LlmProperties properties) {
            super(OBJECT_MAPPER, properties);
            this.completion = completion;
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            return completion;
        }

        @Override
        public String modelName() {
            return "stub-model";
        }
    }

    private static final class LogCapture implements AutoCloseable {

        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender;

        private LogCapture(Logger logger, ListAppender<ILoggingEvent> appender) {
            this.logger = logger;
            this.appender = appender;
        }

        static LogCapture forClass(Class<?> type) {
            Logger logger = (Logger) LoggerFactory.getLogger(type);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            return new LogCapture(logger, appender);
        }

        boolean hasWarnForPhase(String phase) {
            List<ILoggingEvent> events = appender.list;
            for (ILoggingEvent event : events) {
                if (event.getLevel() == Level.WARN && event.getFormattedMessage().contains("phase=" + phase)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
