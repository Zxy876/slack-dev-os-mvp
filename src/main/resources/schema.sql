CREATE TABLE IF NOT EXISTS workflow (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS action (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload TEXT NULL,
    worker_id VARCHAR(64) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    max_retry_count INT NOT NULL DEFAULT 3,
    backoff_seconds INT NOT NULL DEFAULT 5,
    execution_timeout_seconds INT NOT NULL DEFAULT 300,
    lease_expire_at DATETIME NULL,
    next_run_at DATETIME NULL,
    claim_time DATETIME NULL,
    first_renew_time DATETIME NULL,
    last_renew_time DATETIME NULL,
    submit_time DATETIME NULL,
    reclaim_time DATETIME NULL,
    lease_renew_success_count INT NOT NULL DEFAULT 0,
    lease_renew_failure_count INT NOT NULL DEFAULT 0,
    last_lease_renew_at DATETIME NULL,
    execution_started_at DATETIME NULL,
    last_execution_duration_ms BIGINT NULL,
    last_reclaim_reason VARCHAR(64) NULL,
    error_message VARCHAR(512) NULL,
    slack_thread_id VARCHAR(128) NULL,
    notepad_ref TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_action_workflow (workflow_id),
    INDEX idx_action_status (status),
    INDEX idx_action_type (type),
    INDEX idx_action_lease_expire (lease_expire_at),
    INDEX idx_action_next_run (next_run_at)
);

CREATE TABLE IF NOT EXISTS action_dependency (
    id BIGINT PRIMARY KEY,
    workflow_id BIGINT NOT NULL,
    upstream_action_id BIGINT NOT NULL,
    downstream_action_id BIGINT NOT NULL,
    UNIQUE KEY uk_action_dependency (upstream_action_id, downstream_action_id),
    INDEX idx_dependency_upstream (upstream_action_id),
    INDEX idx_dependency_downstream (downstream_action_id)
);

CREATE TABLE IF NOT EXISTS worker (
    id VARCHAR(64) PRIMARY KEY,
    capabilities TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_heartbeat_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_worker_status (status),
    INDEX idx_worker_heartbeat (last_heartbeat_at)
);

CREATE TABLE IF NOT EXISTS action_log (
    id BIGINT PRIMARY KEY,
    action_id BIGINT NOT NULL,
    worker_id VARCHAR(64) NOT NULL,
    result TEXT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_action_log_action (action_id)
);

CREATE TABLE IF NOT EXISTS design_task (
    id VARCHAR(64) PRIMARY KEY,
    workflow_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    input_type VARCHAR(32) NOT NULL,
    prompt_text TEXT NULL,
    design_image_url VARCHAR(512) NULL,
    progress INT NOT NULL DEFAULT 0,
    stage_label VARCHAR(128) NULL,
    result_model_url VARCHAR(512) NULL,
    result_thumbnail_url VARCHAR(512) NULL,
    error_code VARCHAR(64) NULL,
    error_message TEXT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    INDEX idx_design_task_workflow_id (workflow_id),
    INDEX idx_design_task_status (status),
    INDEX idx_design_task_created_at (created_at)
);

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'design_task' AND COLUMN_NAME = 'workflow_id') = 0,
    'ALTER TABLE design_task ADD COLUMN workflow_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'max_retry_count') = 0,
    'ALTER TABLE action ADD COLUMN max_retry_count INT NOT NULL DEFAULT 3',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'backoff_seconds') = 0,
    'ALTER TABLE action ADD COLUMN backoff_seconds INT NOT NULL DEFAULT 5',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'execution_timeout_seconds') = 0,
    'ALTER TABLE action ADD COLUMN execution_timeout_seconds INT NOT NULL DEFAULT 300',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'lease_expire_at') = 0,
    'ALTER TABLE action ADD COLUMN lease_expire_at DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'next_run_at') = 0,
    'ALTER TABLE action ADD COLUMN next_run_at DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'claim_time') = 0,
    'ALTER TABLE action ADD COLUMN claim_time DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'first_renew_time') = 0,
    'ALTER TABLE action ADD COLUMN first_renew_time DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'last_renew_time') = 0,
    'ALTER TABLE action ADD COLUMN last_renew_time DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'submit_time') = 0,
    'ALTER TABLE action ADD COLUMN submit_time DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'reclaim_time') = 0,
    'ALTER TABLE action ADD COLUMN reclaim_time DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'lease_renew_success_count') = 0,
    'ALTER TABLE action ADD COLUMN lease_renew_success_count INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'lease_renew_failure_count') = 0,
    'ALTER TABLE action ADD COLUMN lease_renew_failure_count INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'last_lease_renew_at') = 0,
    'ALTER TABLE action ADD COLUMN last_lease_renew_at DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'execution_started_at') = 0,
    'ALTER TABLE action ADD COLUMN execution_started_at DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'last_execution_duration_ms') = 0,
    'ALTER TABLE action ADD COLUMN last_execution_duration_ms BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'last_reclaim_reason') = 0,
    'ALTER TABLE action ADD COLUMN last_reclaim_reason VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worker' AND COLUMN_NAME = 'last_heartbeat_at') = 0,
    'ALTER TABLE worker ADD COLUMN last_heartbeat_at DATETIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'slack_thread_id') = 0,
    'ALTER TABLE action ADD COLUMN slack_thread_id VARCHAR(128) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'action' AND COLUMN_NAME = 'notepad_ref') = 0,
    'ALTER TABLE action ADD COLUMN notepad_ref TEXT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;