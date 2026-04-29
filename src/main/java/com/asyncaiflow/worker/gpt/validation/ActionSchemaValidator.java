package com.asyncaiflow.worker.gpt.validation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.asyncaiflow.worker.sdk.WorkerClientException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

public class ActionSchemaValidator {

    private final ObjectMapper objectMapper;
    private final ActionSchemaResolver resolver;
    private final JsonSchemaFactory schemaFactory;
    private final ConcurrentHashMap<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public ActionSchemaValidator(ObjectMapper objectMapper, ActionSchemaResolver resolver) {
        this.objectMapper = objectMapper;
        this.resolver = resolver;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    public ValidationReport validatePayload(String actionType, JsonNode payloadNode) {
        return validateNode(actionType, payloadNode, true);
    }

    public ValidationReport validateResult(String actionType, String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return ValidationReport.parseError("result JSON is blank");
        }

        JsonNode resultNode;
        try {
            resultNode = objectMapper.readTree(resultJson);
        } catch (IOException exception) {
            return ValidationReport.parseError("result JSON parse failed: " + exception.getMessage());
        }

        return validateNode(actionType, resultNode, false);
    }

    private ValidationReport validateNode(String actionType, JsonNode node, boolean payloadPhase) {
        Optional<ActionSchemaResolver.SchemaMapping> schemaMapping = resolver.resolve(actionType);
        if (schemaMapping.isEmpty()) {
            return ValidationReport.skipped("no schema mapping for actionType=" + actionType);
        }

        String schemaPath = payloadPhase
                ? schemaMapping.get().payloadSchemaPath()
                : schemaMapping.get().resultSchemaPath();

        JsonSchema schema;
        try {
            schema = loadSchema(schemaPath);
        } catch (RuntimeException exception) {
            return ValidationReport.invalid(schemaPath,
                    List.of("failed to load schema resource: " + exception.getMessage()));
        }

        Set<ValidationMessage> validationMessages = schema.validate(node);
        if (validationMessages.isEmpty()) {
            return ValidationReport.valid(schemaPath);
        }

        List<String> errors = new ArrayList<>();
        for (ValidationMessage message : validationMessages) {
            errors.add(message.getMessage());
        }
        errors.sort(String::compareTo);
        return ValidationReport.invalid(schemaPath, errors);
    }

    private JsonSchema loadSchema(String schemaPath) {
        return schemaCache.computeIfAbsent(schemaPath, this::loadSchemaInternal);
    }

    private JsonSchema loadSchemaInternal(String schemaPath) {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(schemaPath)) {
            if (inputStream == null) {
                throw new WorkerClientException("schema file not found on classpath: " + schemaPath);
            }
            JsonNode schemaNode = objectMapper.readTree(inputStream);
            return schemaFactory.getSchema(schemaNode);
        } catch (IOException exception) {
            throw new WorkerClientException("failed to load schema file: " + schemaPath, exception);
        }
    }

    public record ValidationReport(
            boolean parseable,
            boolean valid,
            boolean skipped,
            String schemaPath,
            List<String> errors
    ) {

        public static ValidationReport valid(String schemaPath) {
            return new ValidationReport(true, true, false, schemaPath, List.of());
        }

        public static ValidationReport invalid(String schemaPath, List<String> errors) {
            return new ValidationReport(true, false, false, schemaPath, errors == null ? List.of() : errors);
        }

        public static ValidationReport parseError(String message) {
            return new ValidationReport(false, false, false, null, List.of(message));
        }

        public static ValidationReport skipped(String reason) {
            return new ValidationReport(true, true, true, null, List.of(reason));
        }

        public String errorSummary() {
            return String.join(" | ", errors);
        }
    }
}
