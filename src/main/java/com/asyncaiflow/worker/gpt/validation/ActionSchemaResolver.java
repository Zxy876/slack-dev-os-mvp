package com.asyncaiflow.worker.gpt.validation;

import java.util.Optional;

public class ActionSchemaResolver {

    private final String schemaBasePath;

    public ActionSchemaResolver(String schemaBasePath) {
        this.schemaBasePath = normalizeBasePath(schemaBasePath);
    }

    public Optional<SchemaMapping> resolve(String actionType) {
        if ("design_solution".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("design_solution.payload.schema.json"),
                    schemaPath("design_solution.result.schema.json")
            ));
        }

        if ("review_code".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("review_code.payload.schema.json"),
                    schemaPath("review_code.result.schema.json")
            ));
        }

        if ("generate_explanation".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("generate_explanation.payload.schema.json"),
                    schemaPath("generate_explanation.result.schema.json")
            ));
        }

        if ("generate_patch".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                schemaPath("generate_patch.payload.schema.json"),
                schemaPath("generate_patch.result.schema.json")
            ));
        }

        if ("review_patch".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                schemaPath("review_patch.payload.schema.json"),
                schemaPath("review_patch.result.schema.json")
            ));
        }

        if ("search_code".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("search_code.payload.schema.json"),
                    schemaPath("search_code.result.schema.json")
            ));
        }

        if ("read_file".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("read_file.payload.schema.json"),
                    schemaPath("read_file.result.schema.json")
            ));
        }

        if ("load_code".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                schemaPath("load_code.payload.schema.json"),
                schemaPath("load_code.result.schema.json")
            ));
        }

        if ("search_semantic".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("search_semantic.payload.schema.json"),
                    schemaPath("search_semantic.result.schema.json")
            ));
        }

        if ("build_context_pack".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("build_context_pack.payload.schema.json"),
                    schemaPath("build_context_pack.result.schema.json")
            ));
        }

        if ("create_branch".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("create_branch.payload.schema.json"),
                    schemaPath("create_branch.result.schema.json")
            ));
        }

        if ("apply_patch".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("apply_patch.payload.schema.json"),
                    schemaPath("apply_patch.result.schema.json")
            ));
        }

        if ("commit_changes".equals(actionType)) {
            return Optional.of(new SchemaMapping(
                    schemaPath("commit_changes.payload.schema.json"),
                    schemaPath("commit_changes.result.schema.json")
            ));
        }

        return Optional.empty();
    }

    private String schemaPath(String fileName) {
        return schemaBasePath + "/" + fileName;
    }

    private static String normalizeBasePath(String value) {
        String fallback = "schemas/v1";
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String normalized = value.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? fallback : normalized;
    }

    public record SchemaMapping(String payloadSchemaPath, String resultSchemaPath) {
    }
}
