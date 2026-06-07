ALTER TABLE output_adapters ADD COLUMN config_params TEXT;

DROP TRIGGER IF EXISTS validate_input_adapter_type;

CREATE TRIGGER validate_input_adapter_type
BEFORE INSERT ON input_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('TcpInputAdapter', 'UdpInputAdapter', 'HttpInputAdapter', 'KafkaInputAdapter', 'SnmpInputAdapter', 'RabbitMqInputAdapter', 'TcpMtlsGzipInputAdapter', 'FileInputAdapter', 'FakeInputAdapter', 'tcp', 'udp', 'http', 'kafka', 'snmp', 'rabbitmq', 'tcp_mtls_gzip', 'file', 'fake')
BEGIN
    SELECT RAISE(ABORT, 'Invalid input adapter type');
END;

DROP TRIGGER IF EXISTS validate_output_adapter_type;

CREATE TRIGGER validate_output_adapter_type
BEFORE INSERT ON output_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('ConsoleOutputAdapter', 'TcpOutputAdapter', 'HttpOutputAdapter', 'KafkaOutputAdapter', 'OpenSearchOutputAdapter', 'RabbitMQAdapter', 'MariaDbOutputAdapter', 'BenchmarkAdapter', 'console', 'tcp', 'http', 'kafka', 'opensearch', 'rabbitmq', 'mariadb', 'benchmark')
BEGIN
    SELECT RAISE(ABORT, 'Invalid output adapter type');
END;
