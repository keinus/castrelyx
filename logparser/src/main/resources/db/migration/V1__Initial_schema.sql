-- V1__Initial_schema.sql
-- Consolidated schema for LogParser application
-- Includes all changes up to V7

-- 공통 설정 테이블
CREATE TABLE IF NOT EXISTS config_settings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT,
    data_type VARCHAR(50),
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

-- 설정 이력 테이블
CREATE TABLE IF NOT EXISTS config_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    entity_type VARCHAR(50) NOT NULL,
    entity_id INTEGER NOT NULL,
    action VARCHAR(20) NOT NULL,
    old_data TEXT,
    new_data TEXT,
    changed_by VARCHAR(100),
    changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 설정 버전 관리 테이블
CREATE TABLE IF NOT EXISTS configuration_versions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version_name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    status VARCHAR(20) DEFAULT 'DRAFT',
    config_snapshot TEXT,
    created_by VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    applied_at TIMESTAMP
);

-- 입력 어댑터 설정 테이블
CREATE TABLE IF NOT EXISTS input_adapters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(100) NOT NULL,
    messagetype VARCHAR(100) NOT NULL, -- UNIQUE constraint removed
    host VARCHAR(255),
    port INTEGER,
    path VARCHAR(500),
    topicid VARCHAR(255),
    bootstrapservers VARCHAR(500),
    group_id VARCHAR(255),
    codec VARCHAR(50),
    path_pattern VARCHAR(500),
    is_from_beginning BOOLEAN DEFAULT 0,
    buffer_size INTEGER,
    timeout_ms INTEGER,
    enabled BOOLEAN DEFAULT 1,
    worker_threads INTEGER,
    queue_size INTEGER,
    config_params TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

-- 인덱스 추가
CREATE INDEX IF NOT EXISTS idx_input_adapters_type ON input_adapters(type);
CREATE INDEX IF NOT EXISTS idx_input_adapters_messagetype ON input_adapters(messagetype);

-- 파서 설정 테이블
CREATE TABLE IF NOT EXISTS parsers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(100) NOT NULL,
    messagetype VARCHAR(100) NOT NULL,
    param TEXT,
    priority INTEGER DEFAULT 0,
    enabled BOOLEAN DEFAULT 1,
    continue_on_failure BOOLEAN DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_parsers_messagetype ON parsers(messagetype);

-- 변환 설정 테이블
CREATE TABLE IF NOT EXISTS transforms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(100) NOT NULL,
    messagetype VARCHAR(100) NOT NULL,
    priority INTEGER DEFAULT 0,
    filter_pass TEXT,
    filter_drop TEXT,
    add_properties TEXT,
    remove_properties TEXT,
    config_params TEXT,
    enabled BOOLEAN DEFAULT 1,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_transforms_messagetype ON transforms(messagetype);

-- 출력 어댑터 설정 테이블
CREATE TABLE IF NOT EXISTS output_adapters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type VARCHAR(100) NOT NULL,
    messagetype VARCHAR(100) NOT NULL,
    host VARCHAR(255),
    port INTEGER,
    url VARCHAR(1000),
    method VARCHAR(20),
    headers TEXT,
    topicid VARCHAR(255),
    bootstrapservers VARCHAR(500),
    key VARCHAR(255),
    index_template VARCHAR(255),
    os_username VARCHAR(255),
    os_password VARCHAR(500),
    action VARCHAR(50),
    routingkey VARCHAR(255),
    exchange VARCHAR(255),
    rmq_username VARCHAR(255),
    rmq_password VARCHAR(500),
    rmq_port INTEGER,
    tagpass TEXT,
    batch_size INTEGER,
    flush_interval_ms INTEGER,
    retry_count INTEGER,
    retry_delay_ms INTEGER,
    add_origin_text BOOLEAN DEFAULT 0,
    enabled BOOLEAN DEFAULT 1,
    timeout_ms INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_output_adapters_messagetype ON output_adapters(messagetype);

-- Triggers for validation

-- input_adapters: type 검증
CREATE TRIGGER IF NOT EXISTS validate_input_adapter_type
BEFORE INSERT ON input_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('TcpInputAdapter', 'TlsTcpInputAdapter', 'UdpInputAdapter', 'HttpInputAdapter', 'HttpsInputAdapter', 'KafkaInputAdapter', 'SnmpInputAdapter', 'RabbitMqInputAdapter', 'TlsRabbitMqInputAdapter', 'TcpMtlsGzipInputAdapter', 'FileInputAdapter', 'FakeInputAdapter', 'tcp', 'tls_tcp', 'tlstcp', 'udp', 'http', 'https', 'kafka', 'snmp', 'rabbitmq', 'tls_rabbitmq', 'tlsrabbitmq', 'tcp_mtls_gzip', 'file', 'fake')
BEGIN
    SELECT RAISE(ABORT, 'Invalid input adapter type');
END;

-- parsers: type 검증
CREATE TRIGGER IF NOT EXISTS validate_parser_type
BEFORE INSERT ON parsers
FOR EACH ROW
WHEN NEW.type NOT IN ('JsonParser', 'GrokParser', 'RegexParser', 'RFC3164SyslogParser', 'RFC5424SyslogParser', 'HttpParser', 'json', 'grok', 'regex', 'rfc3164', 'rfc5424', 'http')
BEGIN
    SELECT RAISE(ABORT, 'Invalid parser type');
END;

-- transforms: type 검증
CREATE TRIGGER IF NOT EXISTS validate_transform_type
BEFORE INSERT ON transforms
FOR EACH ROW
WHEN NEW.type NOT IN ('filter', 'add_property', 'remove_property', 'Filter', 'AddProperty', 'RemoveProperty')
BEGIN
    SELECT RAISE(ABORT, 'Invalid transform type');
END;

-- output_adapters: type 검증
CREATE TRIGGER IF NOT EXISTS validate_output_adapter_type
BEFORE INSERT ON output_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('ConsoleOutputAdapter', 'TcpOutputAdapter', 'HttpOutputAdapter', 'KafkaOutputAdapter', 'OpenSearchOutputAdapter', 'RabbitMQAdapter', 'MariaDbOutputAdapter', 'ClickHouseOutputAdapter', 'BenchmarkAdapter', 'console', 'tcp', 'http', 'kafka', 'opensearch', 'rabbitmq', 'mariadb', 'clickhouse', 'benchmark')
BEGIN
    SELECT RAISE(ABORT, 'Invalid output adapter type');
END;

-- configuration_versions: status 검증
CREATE TRIGGER IF NOT EXISTS validate_version_status
BEFORE INSERT ON configuration_versions
FOR EACH ROW
WHEN NEW.status NOT IN ('DRAFT', 'ACTIVE', 'ARCHIVED')
BEGIN
    SELECT RAISE(ABORT, 'Invalid version status');
END;
