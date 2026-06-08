DROP TRIGGER IF EXISTS validate_output_adapter_type;

CREATE TRIGGER validate_output_adapter_type
BEFORE INSERT ON output_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('ConsoleOutputAdapter', 'TcpOutputAdapter', 'HttpOutputAdapter', 'KafkaOutputAdapter', 'OpenSearchOutputAdapter', 'RabbitMQAdapter', 'MariaDbOutputAdapter', 'ClickHouseOutputAdapter', 'BenchmarkAdapter', 'console', 'tcp', 'http', 'kafka', 'opensearch', 'rabbitmq', 'mariadb', 'clickhouse', 'benchmark')
BEGIN
    SELECT RAISE(ABORT, 'Invalid output adapter type');
END;
