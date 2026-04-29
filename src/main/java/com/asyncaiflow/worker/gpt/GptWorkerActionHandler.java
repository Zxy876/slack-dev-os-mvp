package com.asyncaiflow.worker.gpt;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.asyncaiflow.worker.gpt.validation.ActionSchemaValidator;
import com.asyncaiflow.worker.gpt.validation.SchemaValidationMode;
import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class GptWorkerActionHandler implements WorkerActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GptWorkerActionHandler.class);

        private static final Pattern FENCED_CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:diff|patch)?\\s*(.*?)```", Pattern.DOTALL);

    private static final Set<String> SUPPORTED_ACTION_TYPES = Set.of(
            "design_solution",
            "review_code",
            "generate_explanation",
            "generate_patch",
            "review_patch"
    );

    private final ObjectMapper objectMapper;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ActionSchemaValidator schemaValidator;
    private final SchemaValidationMode validationMode;

    public GptWorkerActionHandler(
            ObjectMapper objectMapper,
            OpenAiCompatibleLlmClient llmClient,
            ActionSchemaValidator schemaValidator,
            SchemaValidationMode validationMode) {
        this.objectMapper = objectMapper;
        this.llmClient = llmClient;
        this.schemaValidator = schemaValidator;
        this.validationMode = validationMode == null ? SchemaValidationMode.WARN : validationMode;
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!SUPPORTED_ACTION_TYPES.contains(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "GPT worker supports design_solution, review_code, generate_explanation, generate_patch and review_patch"
            );
        }

        PayloadParseResult payloadParseResult = parsePayload(assignment.payload());
        if (!payloadParseResult.parseable()) {
            LOGGER.warn("schema_validation phase=payload_parse mode={} actionId={} actionType={} errors={}",
                    validationMode, assignment.actionId(), assignment.type(), payloadParseResult.errorMessage());
            return WorkerExecutionResult.failed("invalid payload json", payloadParseResult.errorMessage());
        }

        JsonNode payload = payloadParseResult.payloadNode();
        if (validationMode != SchemaValidationMode.OFF) {
            ActionSchemaValidator.ValidationReport payloadValidation =
                schemaValidator.validatePayload(assignment.type(), payload);
            WorkerExecutionResult payloadGateResult = handleValidationGate(
                "payload",
                assignment,
                payloadValidation,
                "payload schema validation failed");
            if (payloadGateResult != null) {
            return payloadGateResult;
            }
        }

        Prompt prompt = buildPrompt(assignment.type(), payload);
        try {
            String completion = llmClient.complete(prompt.systemPrompt(), prompt.userPrompt());
            String resultJson = buildResultJson(assignment, payload, completion);

                if (validationMode != SchemaValidationMode.OFF) {
                ActionSchemaValidator.ValidationReport resultValidation =
                    schemaValidator.validateResult(assignment.type(), resultJson);
                if (!resultValidation.parseable()) {
                    LOGGER.warn("schema_validation phase=result_parse mode={} actionId={} actionType={} errors={}",
                        validationMode, assignment.actionId(), assignment.type(), resultValidation.errorSummary());
                    return WorkerExecutionResult.failed("invalid result json", resultValidation.errorSummary());
                }

                WorkerExecutionResult resultGateResult = handleValidationGate(
                    "result",
                    assignment,
                    resultValidation,
                    "result schema validation failed");
                if (resultGateResult != null) {
                    return resultGateResult;
                }
            }

            LOGGER.info("gpt_execution_succeeded actionId={} actionType={} summary={}",
                    assignment.actionId(), assignment.type(), summarize(completion, 160));

            return WorkerExecutionResult.succeeded(resultJson);
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("GPT worker failed to execute actionId={} type={}",
                    assignment.actionId(), assignment.type(), exception);
            return WorkerExecutionResult.failed("gpt execution failed", exception.getMessage());
        }
    }

    private WorkerExecutionResult handleValidationGate(
            String phase,
            ActionAssignment assignment,
            ActionSchemaValidator.ValidationReport report,
            String strictFailureResult) {
        if (validationMode == SchemaValidationMode.OFF || report.skipped() || report.valid()) {
            return null;
        }

        String schemaPath = report.schemaPath() == null ? "n/a" : report.schemaPath();
        String errors = report.errorSummary();

        if (validationMode == SchemaValidationMode.STRICT) {
            LOGGER.warn("schema_validation phase={} mode={} actionId={} actionType={} schemaPath={} strict=true errors={}",
                    phase, validationMode, assignment.actionId(), assignment.type(), schemaPath, errors);
            return WorkerExecutionResult.failed(strictFailureResult, errors);
        }

        LOGGER.warn("schema_validation phase={} mode={} actionId={} actionType={} schemaPath={} strict=false errors={}",
                phase, validationMode, assignment.actionId(), assignment.type(), schemaPath, errors);
        return null;
    }

    private Prompt buildPrompt(String actionType, JsonNode payload) {
        return switch (actionType) {
            case "design_solution" -> buildDesignSolutionPrompt(payload);
            case "review_code" -> buildReviewCodePrompt(payload);
            case "generate_explanation" -> buildGenerateExplanationPrompt(payload);
            case "generate_patch" -> buildGeneratePatchPrompt(payload);
            case "review_patch" -> buildReviewPatchPrompt(payload);
            default -> throw new IllegalArgumentException("unsupported action type: " + actionType);
        };
    }

    private Prompt buildDesignSolutionPrompt(JsonNode payload) {
        String issue = firstNonBlank(
                payload.path("issue").asText(null),
                payload.path("problem").asText(null),
                payload.path("title").asText(null),
                "No issue provided"
        );

        String context = renderPayloadValue(payload.get("context"));
        String constraints = renderStringList(payload.get("constraints"));

        String systemPrompt = "You are a pragmatic senior software architect. " +
            "Always respond in Simplified Chinese and format output in Markdown. " +
            "Use sections exactly: 结论, 发现, 代码位置, 建议修复, 风险. " +
            "Ground recommendations in provided evidence.";

        String userPrompt = """
            Action: design_solution
            Issue:
            %s

            Context:
            %s

            Constraints:
            %s

            Return concise, implementation-ready guidance.
            """.formatted(issue, context, constraints);

        return new Prompt(systemPrompt, userPrompt);
    }

    private Prompt buildReviewCodePrompt(JsonNode payload) {
        String reviewFocus = firstNonBlank(
                payload.path("focus").asText(null),
                payload.path("reviewFocus").asText(null),
                "correctness, reliability and maintainability"
        );

        String diff = renderPayloadValue(payload.get("diff"));
        String code = renderPayloadValue(payload.get("code"));
        String context = renderPayloadValue(payload.get("context"));
        String knownIssues = renderStringList(payload.get("knownIssues"));

        String systemPrompt = "You are a strict senior code reviewer. " +
            "Always respond in Simplified Chinese and format output in Markdown. " +
            "Use sections exactly: 结论, 发现, 代码位置, 建议修复, 风险. " +
            "Prioritize concrete defects over generic advice.";

        String userPrompt = """
            Action: review_code
            Focus:
            %s

            Context:
            %s

            Diff:
            %s

            Code:
            %s

            Known issues:
            %s

            If code or diff is missing, state evidence limits clearly before conclusions.
            """.formatted(reviewFocus, context, diff, code, knownIssues);

        return new Prompt(systemPrompt, userPrompt);
    }

    private Prompt buildGenerateExplanationPrompt(JsonNode payload) {
        String issue = firstNonBlank(
                payload.path("issue").asText(null),
                payload.path("problem").asText(null),
                payload.path("title").asText(null),
                "No issue provided"
        );
        String repoContext = firstNonBlank(
                payload.path("repo_context").asText(null),
                payload.path("context").asText(null),
                ""
        );
        String file = payload.path("file").asText("");
        String module = payload.path("module").asText("");
        String gatheredContext = renderPayloadValue(payload.get("gathered_context"));

        String systemPrompt = "You are a pragmatic senior engineer explaining how a codebase works to another engineer. " +
            "Always respond in Simplified Chinese and format output in Markdown. " +
            "Use sections exactly: 结论, 发现, 代码位置, 建议修复, 风险. " +
            "Separate confirmed facts from inference and call out missing context explicitly.";

        String userPrompt = """
            Action: generate_explanation
            Issue:
            %s

            Repo context:
            %s

            File:
            %s

            Module:
            %s

            Gathered context:
            %s

            Explain concrete flow and components with verifiable evidence.
            """.formatted(
            issue,
            promptValue(repoContext),
            promptValue(file),
            promptValue(module),
            promptValue(gatheredContext));

        return new Prompt(systemPrompt, userPrompt);
    }

    private Prompt buildGeneratePatchPrompt(JsonNode payload) {
    String issue = firstNonBlank(
        payload.path("issue").asText(null),
        payload.path("problem").asText(null),
        payload.path("title").asText(null),
        "No issue provided"
    );
    String file = firstNonBlank(
        payload.path("file").asText(null),
        payload.path("path").asText(null),
        "n/a"
    );
    String context = renderPayloadValue(payload.get("context"));
    String code = renderPayloadValue(payload.get("code"));
    String designSummary = firstNonBlank(
        payload.path("designSummary").asText(null),
        payload.path("design").asText(null),
        payload.path("solution").asText(null),
        "n/a"
    );
    String constraints = renderStringList(payload.get("constraints"));

    String systemPrompt = "You are a pragmatic senior engineer that prepares minimal, safe code patches. " +
        "Always respond in Simplified Chinese and format output in Markdown. " +
        "Use sections exactly: 结论, 修改文件, 统一补丁, 风险. " +
        "In section 统一补丁, output exactly one fenced diff block using unified diff format that can be applied by git apply.";

    String userPrompt = """
        Action: generate_patch
        Issue:
        %s

        File:
        %s

        Design summary:
        %s

        Context:
        %s

        Code:
        %s

        Constraints:
        %s

        Return a minimal unified diff only for the necessary fix, plus concise review notes in the required sections.
        """.formatted(issue, promptValue(file), promptValue(designSummary), promptValue(context), promptValue(code), constraints);

    return new Prompt(systemPrompt, userPrompt);
    }

    private Prompt buildReviewPatchPrompt(JsonNode payload) {
    String issue = firstNonBlank(
        payload.path("issue").asText(null),
        payload.path("problem").asText(null),
        payload.path("title").asText(null),
        "No issue provided"
    );
    String file = firstNonBlank(
        payload.path("file").asText(null),
        payload.path("path").asText(null),
        "n/a"
    );
    String context = renderPayloadValue(payload.get("context"));
    String code = renderPayloadValue(payload.get("code"));
    String designSummary = firstNonBlank(
        payload.path("designSummary").asText(null),
        payload.path("design").asText(null),
        payload.path("solution").asText(null),
        "n/a"
    );
    String patch = renderPayloadValue(payload.get("patch"));
    String knownRisks = renderStringList(payload.get("knownRisks"));

    String systemPrompt = "You are a strict senior reviewer validating unified diffs before they are applied. " +
        "Always respond in Simplified Chinese and format output in Markdown. " +
        "Use sections exactly: 结论, 发现, 是否可应用, 修订补丁, 风险. " +
        "In section 修订补丁, output exactly one fenced diff block with the final patch to apply. " +
        "If the patch is unsafe, explain blocking issues clearly.";

    String userPrompt = """
        Action: review_patch
        Issue:
        %s

        File:
        %s

        Design summary:
        %s

        Context:
        %s

        Current code:
        %s

        Candidate patch:
        %s

        Known risks:
        %s

        Verify correctness and safety. Keep the patch minimal, and return the final unified diff in the required section.
        """.formatted(issue, promptValue(file), promptValue(designSummary), promptValue(context), promptValue(code), promptValue(patch), knownRisks);

    return new Prompt(systemPrompt, userPrompt);
    }

    private String buildResultJson(ActionAssignment assignment, JsonNode payload, String completion) throws IOException {
        return switch (assignment.type()) {
        case "design_solution" -> buildDesignSolutionResultJson(completion);
            case "review_code" -> buildReviewCodeResultJson(completion);
            case "generate_explanation" -> buildGenerateExplanationResultJson(completion);
        case "generate_patch" -> buildGeneratePatchResultJson(payload, completion);
        case "review_patch" -> buildReviewPatchResultJson(payload, completion);
        default -> throw new IllegalArgumentException("unsupported action type: " + assignment.type());
        };
    }

    private String buildDesignSolutionResultJson(String completion) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", "gpt-worker");
        result.put("model", llmClient.modelName());
        result.put("summary", summarize(completion, 220));
        result.put("content", completion);
        result.put("confidence", 0.65D);
        return objectMapper.writeValueAsString(result);
    }

    private String buildReviewCodeResultJson(String completion) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", "gpt-worker");
        result.put("model", llmClient.modelName());

        ArrayNode findings = result.putArray("findings");
        ObjectNode finding = findings.addObject();
        finding.put("severity", "major");
        finding.put("title", "LLM review summary");
        finding.put("detail", summarize(completion, 900));

        result.put("content", completion);
        result.put("confidence", 0.62D);
        return objectMapper.writeValueAsString(result);
    }

    private String buildGenerateExplanationResultJson(String completion) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", "gpt-worker");
        result.put("model", llmClient.modelName());
        result.put("summary", summarize(completion, 220));
        result.put("content", completion);
        result.put("confidence", 0.71D);
        return objectMapper.writeValueAsString(result);
    }

    private String buildGeneratePatchResultJson(JsonNode payload, String completion) throws IOException {
        String patch = extractUnifiedDiff(completion);
        if (patch.isBlank()) {
            throw new IllegalArgumentException("generate_patch completion did not contain a unified diff block");
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", "gpt-worker");
        result.put("model", llmClient.modelName());
        result.put("summary", summarize(completion, 220));
        result.put("patch", patch);
        result.put("content", completion);
        result.put("confidence", 0.58D);

        ArrayNode targetFiles = result.putArray("targetFiles");
        for (String targetFile : extractTargetFiles(payload, patch)) {
            targetFiles.add(targetFile);
        }

        return objectMapper.writeValueAsString(result);
    }

    private String buildReviewPatchResultJson(JsonNode payload, String completion) throws IOException {
        String patch = extractUnifiedDiff(completion);
        if (patch.isBlank()) {
            patch = firstNonBlank(payload.path("patch").asText(null));
        }
        if (patch.isBlank()) {
            throw new IllegalArgumentException("review_patch completion did not contain a unified diff block");
        }

        boolean approved = inferPatchApproved(completion);
        if (!approved) {
            throw new IllegalArgumentException("review_patch rejected the candidate patch");
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("schemaVersion", "v1");
        result.put("worker", "gpt-worker");
        result.put("model", llmClient.modelName());
        result.put("summary", summarize(completion, 220));
        result.put("approved", true);
        result.put("patch", patch);
        result.put("content", completion);
        result.put("confidence", 0.63D);

        ArrayNode findings = result.putArray("findings");
        ObjectNode finding = findings.addObject();
        finding.put("severity", "minor");
        finding.put("title", "Patch review summary");
        finding.put("detail", summarize(completion, 900));

        return objectMapper.writeValueAsString(result);
    }

    private static String summarize(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "No summary available";
        }

        String normalized = content.replace('\n', ' ').trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(1, maxLength)) + "...";
    }

    private PayloadParseResult parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return PayloadParseResult.success(objectMapper.createObjectNode());
        }

        try {
            return PayloadParseResult.success(objectMapper.readTree(payload));
        } catch (IOException exception) {
            return PayloadParseResult.failure("payload JSON parse failed: " + exception.getMessage());
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String renderPayloadValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException exception) {
            return node.toString();
        }
    }

    private String promptValue(String value) {
        return (value == null || value.isBlank()) ? "n/a" : value;
    }

    private String renderStringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "n/a";
        }

        if (node.isTextual()) {
            String value = node.asText("").trim();
            return value.isBlank() ? "n/a" : value;
        }

        if (!node.isArray()) {
            return renderPayloadValue(node);
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode item : node) {
            String value = item == null ? "" : item.asText("").trim();
            if (value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append("- ").append(value);
        }

        return builder.length() == 0 ? "n/a" : builder.toString();
    }

    private String extractUnifiedDiff(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        Matcher matcher = FENCED_CODE_BLOCK_PATTERN.matcher(content);
        while (matcher.find()) {
            String block = matcher.group(1).trim();
            if (looksLikeUnifiedDiff(block)) {
                return block;
            }
        }

        String normalized = content.trim();
        int diffIndex = normalized.indexOf("diff --git ");
        if (diffIndex >= 0) {
            String candidate = normalized.substring(diffIndex).trim();
            if (looksLikeUnifiedDiff(candidate)) {
                return candidate;
            }
        }

        int headerIndex = normalized.indexOf("--- ");
        if (headerIndex >= 0) {
            String candidate = normalized.substring(headerIndex).trim();
            if (looksLikeUnifiedDiff(candidate)) {
                return candidate;
            }
        }

        return "";
    }

    private boolean looksLikeUnifiedDiff(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }

        String normalized = candidate.trim();
        return normalized.contains("\n+++ ")
                && (normalized.contains("\n@@ ")
                || normalized.startsWith("diff --git ")
                || normalized.startsWith("--- "));
    }

    private Set<String> extractTargetFiles(JsonNode payload, String patch) {
        LinkedHashSet<String> targetFiles = new LinkedHashSet<>();
        if (patch != null && !patch.isBlank()) {
            for (String line : patch.split("\\R")) {
                if (!line.startsWith("+++ ")) {
                    continue;
                }
                String value = normalizeDiffPath(line.substring(4).trim());
                if (!value.isBlank()) {
                    targetFiles.add(value);
                }
            }
        }

        String payloadFile = firstNonBlank(
                payload.path("file").asText(null),
                payload.path("path").asText(null));
        if (!payloadFile.isBlank()) {
            targetFiles.add(payloadFile);
        }
        return targetFiles;
    }

    private String normalizeDiffPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }

        String normalized = rawPath.trim();
        if ("/dev/null".equals(normalized)) {
            return "";
        }
        if (normalized.startsWith("a/") || normalized.startsWith("b/")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private boolean inferPatchApproved(String completion) {
        if (completion == null || completion.isBlank()) {
            return false;
        }

        String normalized = completion.toLowerCase(Locale.ROOT);
        return !containsAny(normalized,
                "不可应用",
                "不要应用",
                "拒绝",
                "blocking",
                "reject",
                "rejected");
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private record PayloadParseResult(boolean parseable, JsonNode payloadNode, String errorMessage) {

        static PayloadParseResult success(JsonNode payloadNode) {
            return new PayloadParseResult(true, payloadNode, null);
        }

        static PayloadParseResult failure(String errorMessage) {
            return new PayloadParseResult(false, null, errorMessage);
        }
    }

    private record Prompt(String systemPrompt, String userPrompt) {
    }
}
