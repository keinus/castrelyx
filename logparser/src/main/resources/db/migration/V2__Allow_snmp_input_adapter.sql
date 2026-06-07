DROP TRIGGER IF EXISTS validate_input_adapter_type;

CREATE TRIGGER validate_input_adapter_type
BEFORE INSERT ON input_adapters
FOR EACH ROW
WHEN NEW.type NOT IN ('TcpInputAdapter', 'UdpInputAdapter', 'HttpInputAdapter', 'KafkaInputAdapter', 'SnmpInputAdapter', 'FileInputAdapter', 'FakeInputAdapter', 'tcp', 'udp', 'http', 'kafka', 'file', 'fake')
BEGIN
    SELECT RAISE(ABORT, 'Invalid input adapter type');
END;
