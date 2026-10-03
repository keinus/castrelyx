DROP TRIGGER IF EXISTS validate_input_adapter_type;

CREATE TRIGGER validate_input_adapter_type
BEFORE INSERT ON input_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('TcpInputAdapter', 'TlsTcpInputAdapter', 'UdpInputAdapter', 'HttpInputAdapter', 'HttpsInputAdapter', 'KafkaInputAdapter', 'SnmpInputAdapter', 'SnmpNotificationInputAdapter', 'RabbitMqInputAdapter', 'TlsRabbitMqInputAdapter', 'TcpMtlsGzipInputAdapter', 'FileInputAdapter', 'FakeInputAdapter', 'tcp', 'tls_tcp', 'tlstcp', 'udp', 'http', 'https', 'kafka', 'snmp', 'snmp_notification', 'rabbitmq', 'tls_rabbitmq', 'tlsrabbitmq', 'tcp_mtls_gzip', 'file', 'fake')
BEGIN
    SELECT RAISE(ABORT, 'Invalid input adapter type');
END;

DROP TRIGGER IF EXISTS validate_input_adapter_type_update;

CREATE TRIGGER validate_input_adapter_type_update
BEFORE UPDATE OF type ON input_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('TcpInputAdapter', 'TlsTcpInputAdapter', 'UdpInputAdapter', 'HttpInputAdapter', 'HttpsInputAdapter', 'KafkaInputAdapter', 'SnmpInputAdapter', 'SnmpNotificationInputAdapter', 'RabbitMqInputAdapter', 'TlsRabbitMqInputAdapter', 'TcpMtlsGzipInputAdapter', 'FileInputAdapter', 'FakeInputAdapter', 'tcp', 'tls_tcp', 'tlstcp', 'udp', 'http', 'https', 'kafka', 'snmp', 'snmp_notification', 'rabbitmq', 'tls_rabbitmq', 'tlsrabbitmq', 'tcp_mtls_gzip', 'file', 'fake')
BEGIN
    SELECT RAISE(ABORT, 'Invalid input adapter type');
END;
