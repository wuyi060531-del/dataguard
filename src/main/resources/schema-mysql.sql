CREATE TABLE IF NOT EXISTS datasets (
    id VARCHAR(36) PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    storage_name VARCHAR(40) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 CHAR(64) NOT NULL,
    created_at BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspection_tasks (
    id VARCHAR(36) PRIMARY KEY,
    dataset_id VARCHAR(36) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    error_code VARCHAR(128),
    error_message TEXT,
    CONSTRAINT fk_task_dataset FOREIGN KEY (dataset_id) REFERENCES datasets(id),
    CONSTRAINT ck_task_terminal CHECK ((status = 'RUNNING' AND completed_at IS NULL)
        OR (status <> 'RUNNING' AND completed_at IS NOT NULL)),
    INDEX idx_tasks_dataset_created (dataset_id, created_at),
    INDEX idx_tasks_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inspection_reports (
    task_id VARCHAR(36) PRIMARY KEY,
    total_records INT NOT NULL CHECK (total_records >= 0),
    valid_records INT NOT NULL CHECK (valid_records >= 0),
    invalid_records INT NOT NULL CHECK (invalid_records >= 0),
    total_issues INT NOT NULL CHECK (total_issues >= 0),
    quality_score DECIMAL(5,2) NOT NULL,
    report_json LONGTEXT NOT NULL,
    CONSTRAINT fk_report_task FOREIGN KEY (task_id) REFERENCES inspection_tasks(id),
    CONSTRAINT ck_report_counts CHECK (valid_records + invalid_records = total_records),
    CONSTRAINT ck_report_score CHECK (quality_score >= 0 AND quality_score <= 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS quality_issues (
    task_id VARCHAR(36) NOT NULL,
    issue_index INT NOT NULL,
    record_number BIGINT NOT NULL,
    line_number BIGINT NOT NULL,
    order_id LONGTEXT,
    field_name LONGTEXT NOT NULL,
    rule_name VARCHAR(128) NOT NULL,
    message LONGTEXT NOT NULL,
    severity VARCHAR(16) NOT NULL CHECK (severity IN ('ERROR', 'WARNING')),
    record_json LONGTEXT NOT NULL,
    PRIMARY KEY (task_id, issue_index),
    CONSTRAINT fk_issue_report FOREIGN KEY (task_id) REFERENCES inspection_reports(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
