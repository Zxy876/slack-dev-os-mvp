package com.asyncaiflow.planner;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PlannerCorpusTest {

    private static final String CORPUS_PATH = "planner/driftsystem-corpus.json";

    private final WorkflowPlanGenerator generator = new WorkflowPlanGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void driftSystemCorpusHasExpectedCoverage() {
        PlannerCorpus corpus = loadCorpus();

        assertTrue(corpus.issues().size() >= 15, "corpus should include at least 15 DriftSystem issues");
        assertTrue(corpus.issues().size() <= 20, "corpus should include at most 20 DriftSystem issues");

        Set<String> classifications = corpus.issues().stream()
                .map(PlannerCorpusIssue::classification)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("understanding", "review", "diagnosis"), classifications);
    }

    @Test
    void driftSystemCorpusIssuesMapToExpectedPlanTypes() {
        PlannerCorpus corpus = loadCorpus();

        for (PlannerCorpusIssue issue : corpus.issues()) {
            assertFalse(issue.issue().isBlank(), "issue text must not be blank");
            assertFalse(issue.classification().isBlank(), "classification must not be blank");
            assertEquals(expectedPlanTypesForClassification(issue.classification()), issue.expectedPlanTypes(),
                    "corpus classification and expected plan types diverged for issue: " + issue.issue());

            List<String> actualPlanTypes = generator.generatePlan(issue.issue(), issue.repoContext(), issue.file())
                    .stream()
                    .map(PlanDraftStep::type)
                    .toList();

            assertEquals(issue.expectedPlanTypes(), actualPlanTypes,
                    () -> "unexpected plan for DriftSystem issue [" + issue.classification() + "] " + issue.issue());
        }
    }

    private List<String> expectedPlanTypesForClassification(String classification) {
        return switch (classification) {
            case "understanding" -> List.of("search_semantic", "build_context_pack", "generate_explanation");
            case "review" -> List.of("search_semantic", "load_code", "build_context_pack", "review_code");
            case "diagnosis" -> List.of(
                    "search_semantic",
                    "load_code",
                    "build_context_pack",
                    "design_solution",
                    "generate_patch",
                    "review_patch",
                    "apply_patch",
                    "commit_changes");
            default -> throw new IllegalArgumentException("unknown classification: " + classification);
        };
    }

    private PlannerCorpus loadCorpus() {
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(CORPUS_PATH)) {
            if (inputStream == null) {
                throw new IllegalStateException("planner corpus resource not found: " + CORPUS_PATH);
            }
            PlannerCorpus corpus = objectMapper.readValue(inputStream, PlannerCorpus.class);
            if (corpus.issues() == null) {
                throw new IllegalStateException("planner corpus issues must not be null");
            }
            return corpus;
        } catch (IOException exception) {
            throw new IllegalStateException("failed to read planner corpus resource: " + CORPUS_PATH, exception);
        }
    }

    private record PlannerCorpus(List<PlannerCorpusIssue> issues) {
    }

    private record PlannerCorpusIssue(
            String issue,
            String classification,
            String repoContext,
            String file,
            List<String> expectedPlanTypes) {
    }
}