package com.asyncaiflow.worker.planner;

import java.io.IOException;
import java.util.List;

import com.asyncaiflow.planner.PlanDraftStep;
import com.asyncaiflow.planner.WorkflowPlanGenerator;
import com.asyncaiflow.worker.sdk.WorkerActionHandler;
import com.asyncaiflow.worker.sdk.WorkerExecutionResult;
import com.asyncaiflow.worker.sdk.model.ActionAssignment;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PlannerWorkerActionHandler implements WorkerActionHandler {

    private static final String SUPPORTED_ACTION_TYPE = "plan_workflow";

    private final ObjectMapper objectMapper;
    private final WorkflowPlanGenerator workflowPlanGenerator;

    public PlannerWorkerActionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.workflowPlanGenerator = new WorkflowPlanGenerator();
    }

    @Override
    public WorkerExecutionResult execute(ActionAssignment assignment) {
        if (!SUPPORTED_ACTION_TYPE.equals(assignment.type())) {
            return WorkerExecutionResult.failed(
                    "unsupported action type",
                    "Planner worker supports only plan_workflow"
            );
        }

        PlannerPayload payload;
        try {
            payload = parsePayload(assignment.payload());
        } catch (IOException exception) {
            return WorkerExecutionResult.failed("invalid payload json", exception.getMessage());
        }

        if (payload.issue().isBlank()) {
            return WorkerExecutionResult.failed("invalid planner payload", "issue must not be blank");
        }

        List<PlanDraftStep> plan = workflowPlanGenerator.generatePlan(
                payload.issue(),
                payload.repoContext(),
                payload.file());

        try {
            return WorkerExecutionResult.succeeded(serializePlan(plan));
        } catch (IOException exception) {
            return WorkerExecutionResult.failed("planner result serialization failed", exception.getMessage());
        }
    }

    private PlannerPayload parsePayload(String rawPayload) throws IOException {
        if (rawPayload == null || rawPayload.isBlank()) {
            return new PlannerPayload("", "", "");
        }
        PlannerPayload payload = objectMapper.readValue(rawPayload, PlannerPayload.class);
        return new PlannerPayload(
                normalize(payload.issue()),
                normalize(payload.repoContext()),
                normalize(payload.file()));
    }

    private String serializePlan(List<PlanDraftStep> plan) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode planNode = result.putArray("plan");

        for (PlanDraftStep step : plan) {
            ObjectNode stepNode = planNode.addObject();
            stepNode.put("type", step.type());
            stepNode.set("payload", objectMapper.valueToTree(step.payload()));

            ArrayNode dependsOnNode = stepNode.putArray("depends_on");
            for (Integer index : step.dependsOn()) {
                dependsOnNode.add(index);
            }
        }

        return objectMapper.writeValueAsString(result);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private record PlannerPayload(
            @JsonProperty("issue") String issue,
            @JsonProperty("repo_context") String repoContext,
            @JsonProperty("file") String file
    ) {
    }
}
