CREATE TABLE IF NOT EXISTS mapping_templates (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    source_message_type VARCHAR(255),
    config_json TEXT NOT NULL,
    created_at VARCHAR(64) NOT NULL,
    updated_at VARCHAR(64) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mapping_templates_name ON mapping_templates(name);
