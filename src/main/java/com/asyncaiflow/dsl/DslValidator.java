package com.asyncaiflow.dsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

public class DslValidator {

    private static final String DEFAULT_SCHEMA_RESOURCE = "schema/design-schema-v0.1.json";

    private final ObjectMapper objectMapper;
    private final JsonSchemaFactory schemaFactory;

    public DslValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    }

    public ValidationResult validateAgainstDefaultSchema(String rawDslJson) {
        JsonSchema schema = loadSchemaFromClasspath(DEFAULT_SCHEMA_RESOURCE);
        return validate(rawDslJson, schema);
    }

    public ValidationResult validateAgainstSchemaFile(String rawDslJson, Path schemaPath) {
        JsonSchema schema = loadSchemaFromFile(schemaPath);
        return validate(rawDslJson, schema);
    }

    private ValidationResult validate(String rawDslJson, JsonSchema schema) {
        JsonNode dslNode;
        try {
            dslNode = objectMapper.readTree(rawDslJson);
        } catch (IOException exception) {
            return ValidationResult.of(List.of("DSL JSON parse failed: " + exception.getMessage()), List.of());
        }

        Set<ValidationMessage> schemaMessages = schema.validate(dslNode);
        List<String> schemaErrors = schemaMessages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .toList();

        List<String> semanticErrors = new ArrayList<>();
        if (schemaErrors.isEmpty()) {
            semanticErrors.addAll(validateTopologySemantics(dslNode));
            semanticErrors.sort(String::compareTo);
        }

        return ValidationResult.of(schemaErrors, semanticErrors);
    }

    private JsonSchema loadSchemaFromClasspath(String resourcePath) {
        try (InputStream inputStream = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IllegalStateException("Schema resource not found: " + resourcePath);
            }
            JsonNode schemaNode = objectMapper.readTree(inputStream);
            return schemaFactory.getSchema(schemaNode);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load schema resource: " + resourcePath, exception);
        }
    }

    private JsonSchema loadSchemaFromFile(Path schemaPath) {
        try {
            JsonNode schemaNode = objectMapper.readTree(Files.newInputStream(schemaPath));
            return schemaFactory.getSchema(schemaNode);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load schema file: " + schemaPath, exception);
        }
    }

    private List<String> validateTopologySemantics(JsonNode dslNode) {
        List<String> errors = new ArrayList<>();
        JsonNode componentsNode = dslNode.path("components");
        JsonNode topologyNode = dslNode.path("topology");

        if (!componentsNode.isArray() || !topologyNode.isArray()) {
            errors.add("components/topology must be arrays");
            return errors;
        }

        Set<String> componentIds = new LinkedHashSet<>();
        for (JsonNode component : componentsNode) {
            String componentId = component.path("id").asText("");
            if (componentId.isBlank()) {
                errors.add("component id must not be blank");
                continue;
            }
            if (!componentIds.add(componentId)) {
                errors.add("duplicate component id: " + componentId);
            }
        }

        Map<String, Integer> degree = new HashMap<>();
        for (String componentId : componentIds) {
            degree.put(componentId, 0);
        }

        Set<String> seamIds = new HashSet<>();
        int index = 0;
        for (JsonNode seam : topologyNode) {
            String seamId = seam.path("id").asText("");
            String componentA = seam.path("componentA").asText("");
            String componentB = seam.path("componentB").asText("");

            if (!seamId.isBlank() && !seamIds.add(seamId)) {
                errors.add("duplicate topology id: " + seamId);
            }

            if (!componentIds.contains(componentA)) {
                errors.add("topology[" + index + "] componentA not found in components: " + componentA);
            }
            if (!componentIds.contains(componentB)) {
                errors.add("topology[" + index + "] componentB not found in components: " + componentB);
            }
            if (!componentA.isBlank() && componentA.equals(componentB)) {
                errors.add("topology[" + index + "] self-loop is not allowed for component: " + componentA);
            }

            if (degree.containsKey(componentA)) {
                degree.put(componentA, degree.get(componentA) + 1);
            }
            if (degree.containsKey(componentB)) {
                degree.put(componentB, degree.get(componentB) + 1);
            }

            index++;
        }

        for (Map.Entry<String, Integer> entry : degree.entrySet()) {
            if (entry.getValue() == 0) {
                errors.add("isolated component detected: " + entry.getKey());
            }
        }

        return errors;
    }

    public record ValidationResult(
            boolean valid,
            List<String> schemaErrors,
            List<String> semanticErrors
    ) {

        public static ValidationResult of(List<String> schemaErrors, List<String> semanticErrors) {
            List<String> safeSchemaErrors = schemaErrors == null ? List.of() : List.copyOf(schemaErrors);
            List<String> safeSemanticErrors = semanticErrors == null ? List.of() : List.copyOf(semanticErrors);
            return new ValidationResult(safeSchemaErrors.isEmpty() && safeSemanticErrors.isEmpty(), safeSchemaErrors, safeSemanticErrors);
        }

        public List<String> allErrors() {
            List<String> errors = new ArrayList<>(schemaErrors);
            errors.addAll(semanticErrors);
            return errors;
        }
    }
}
