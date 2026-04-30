CREATE TABLE IF NOT EXISTS workflow (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS action (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload CLOB,
    worker_id VARCHAR(64),
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 3,
    backoff_seconds INT NOT NULL DEFAULT 5,
    execution_timeout_seconds INT NOT NULL DEFAULT 300,
    lease_expire_at TIMESTAMP,
    next_run_at TIMESTAMP,
    claim_time TIMESTAMP,
    first_renew_time TIMESTAMP,
    last_renew_time TIMESTAMP,
    submit_time TIMESTAMP,
    reclaim_time TIMESTAMP,
    lease_renew_success_count INT NOT NULL DEFAULT 0,
    lease_renew_failure_count INT NOT NULL DEFAULT 0,
    last_lease_renew_at TIMESTAMP,
    execution_started_at TIMESTAMP,
    last_execution_duration_ms BIGINT,
    last_reclaim_reason VARCHAR(64),
    error_message VARCHAR(512),
    slack_thread_id VARCHAR(128),
    notepad_ref CLOB,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS action_dependency (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    upstream_action_id BIGINT NOT NULL,
    downstream_action_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS worker (
    id VARCHAR(64) PRIMARY KEY,
    capabilities CLOB NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_heartbeat_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS action_log (
    id BIGINT PRIMARY KEY,
    action_id BIGINT NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    result CLOB,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

ALTER TABLE action ADD COLUMN IF NOT EXISTS max_retry_count INT DEFAULT 3;
ALTER TABLE action ADD COLUMN IF NOT EXISTS backoff_seconds INT DEFAULT 5;
ALTER TABLE action ADD COLUMN IF NOT EXISTS execution_timeout_seconds INT DEFAULT 300;
ALTER TABLE action ADD COLUMN IF NOT EXISTS lease_expire_at TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS next_run_at TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS claim_time TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS first_renew_time TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS last_renew_time TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS submit_time TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS reclaim_time TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS lease_renew_success_count INT DEFAULT 0;
ALTER TABLE action ADD COLUMN IF NOT EXISTS lease_renew_failure_count INT DEFAULT 0;
ALTER TABLE action ADD COLUMN IF NOT EXISTS last_lease_renew_at TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS execution_started_at TIMESTAMP;
ALTER TABLE action ADD COLUMN IF NOT EXISTS last_execution_duration_ms BIGINT;
ALTER TABLE action ADD COLUMN IF NOT EXISTS last_reclaim_reason VARCHAR(64);

ALTER TABLE worker ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uk_action_dependency
    ON action_dependency (upstream_action_id, downstream_action_id);

CREATE INDEX IF NOT EXISTS idx_action_workflow ON action (workflow_id);
CREATE INDEX IF NOT EXISTS idx_action_status ON action (status);
CREATE INDEX IF NOT EXISTS idx_action_type ON action (type);
CREATE INDEX IF NOT EXISTS idx_action_lease_expire ON action (lease_expire_at);
CREATE INDEX IF NOT EXISTS idx_action_next_run ON action (next_run_at);
CREATE INDEX IF NOT EXISTS idx_dependency_upstream ON action_dependency (upstream_action_id);
CREATE INDEX IF NOT EXISTS idx_dependency_downstream ON action_dependency (downstream_action_id);
CREATE INDEX IF NOT EXISTS idx_action_log_action ON action_log (action_id);
CREATE INDEX IF NOT EXISTS idx_worker_status ON worker (status);
CREATE INDEX IF NOT EXISTS idx_worker_heartbeat ON worker (last_heartbeat_at);