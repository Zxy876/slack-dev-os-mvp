package com.asyncaiflow.design;

public final class DesignWorkflowConstants {

    // Real Python GPT worker (nl_to_design_dsl)
    public static final String NL_DESIGN_ACTION_TYPE = "nl_to_design_dsl";
    public static final String NL_DESIGN_WORKER_ID = "design-gpt-worker-py";

    // BFS topology validation worker (topology_validate)  ← Phase 2 Task 3
    public static final String TOPOLOGY_VALIDATE_ACTION_TYPE = "topology_validate";
    public static final String TOPOLOGY_VALIDATE_WORKER_ID = "bfs-topology-worker-py";

    // DP nesting worker (dp_nesting)  ← Phase 2 Task 4
    public static final String DP_NESTING_ACTION_TYPE = "dp_nesting";
    public static final String DP_NESTING_WORKER_ID = "dp-nesting-worker-py";

    // Raw scan processing worker (process_raw_scan)
    public static final String SCAN_PROCESS_ACTION_TYPE = "process_raw_scan";
    public static final String SCAN_PROCESS_WORKER_ID = "scan-processing-worker-py";

    // Stub workers (kept for offline / no-API-key development)
    public static final String TRANSLATOR_ACTION_TYPE = "design_translate_stub";
    public static final String RENDERER_ACTION_TYPE = "3d_assembly_render";

    public static final String TRANSLATOR_WORKER_ID = "translator-stub-worker";
    public static final String RENDERER_WORKER_ID = "renderer-stub-worker";
    public static final String ASSEMBLY_WORKER_ID = "assembly-worker-py";

    public static final String MOCK_MODEL_URL = "https://mock.cdn/test.glb";
    public static final String MOCK_THUMBNAIL_URL = "https://mock.cdn/test.png";
    public static final String DSL_VERSION = "v1";

    private DesignWorkflowConstants() {
    }
}