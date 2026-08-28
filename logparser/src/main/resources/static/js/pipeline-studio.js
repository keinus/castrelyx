const PipelineStudio = (function() {
    'use strict';

    const SAMPLE_INPUT = `{
  "agent_id": "agent-1234",
  "host": "web-01.castrelyx.local",
  "level": "INFO",
  "message": "User login successful",
  "timestamp": "2025-05-25T12:34:56.789Z",
  "src_ip": "10.0.0.45",
  "dst_port": 443,
  "debug": {
    "trace_id": "abc123",
    "verbose": true
  }
}`;

    const COMMON_TARGETS = [
        'event_time', 'event_category', 'event_type', 'event_action', 'event_result',
        'severity', 'src_ip', 'src_port', 'dst_ip', 'dst_port', 'protocol',
        'src_host', 'dst_host', 'user_name', 'user_id', 'log_source'
    ];

    const STAGES = [
        { key: 'input', number: 1, label: 'Input', icon: 'cell_tower' },
        { key: 'processing', number: 2, label: 'Processing Steps', icon: 'account_tree' },
        { key: 'structured', number: 3, label: 'Structured Transform', icon: 'table_chart' },
        { key: 'output', number: 4, label: 'Output', icon: 'output' }
    ];

    const TYPE_DEFS = {
        input: [
            def('FileInputAdapter', 'File', 'description', 'Tail a UTF-8 log file', ['source', 'advanced'], [
                field('path', 'File path', 'text', { required: true, tab: 'source', help: 'Absolute or service-relative path to the log file.' }),
                field('isFromBeginning', 'Read from beginning', 'boolean', { default: false, tab: 'source', help: 'Only applies when the file is opened for the first time.' }),
                field('host', 'Source host fallback', 'text', { default: 'localhost', tab: 'advanced', help: 'Used as source metadata when the line has no host.' })
            ], 'Reads new UTF-8 lines from a regular file.'),
            def('TcpInputAdapter', 'TCP', 'settings_ethernet', 'Newline-delimited TCP listener', ['connection', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: 'connection' })
            ], 'All interfaces · newline-delimited UTF-8 · one line per event.'),
            def('TlsTcpInputAdapter', 'TLS TCP', 'enhanced_encryption', 'TLS protected TCP listener', ['connection', 'tls', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: ['connection', 'tls'] }),
                ...serverTlsFields()
            ], 'TLS server listener · optional client certificate authentication.'),
            def('UdpInputAdapter', 'UDP', 'radar', 'One datagram per event', ['connection', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: 'connection' })
            ], 'All interfaces · 1 datagram = 1 event · maximum 1,600 bytes.'),
            def('HttpInputAdapter', 'HTTP', 'http', 'Capture complete HTTP requests', ['connection', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: 'connection' })
            ], 'Receives every path and creates one event from the request line, headers, and body.', 'This adapter is a raw HTTP collector and does not guarantee webhook-style responses.'),
            def('HttpsInputAdapter', 'HTTPS', 'https', 'TLS HTTP request collector', ['connection', 'tls', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: ['connection', 'tls'] }),
                ...serverTlsFields()
            ], 'Receives all HTTPS paths · full request becomes one event.', 'This adapter is a raw request collector; codec and path routing are not runtime settings.'),
            def('KafkaInputAdapter', 'Kafka', 'hub', 'Consume a Kafka topic', ['connection', 'subscription', 'advanced'], [
                field('bootstrapservers', 'Bootstrap servers', 'text', { required: true, tab: 'connection', help: 'Comma-separated broker addresses.' }),
                field('topicid', 'Topic', 'text', { required: true, tab: 'subscription' }),
                field('groupId', 'Consumer group', 'text', { tab: 'subscription', help: 'When empty, a UUID is generated at each startup.' })
            ], 'Consumes records from a Kafka topic as pipeline events.'),
            def('SnmpInputAdapter', 'SNMP poller', 'sensors', 'Poll one or more SNMP targets', ['polling', 'targets', 'advanced'], [
                field('configParams.version', 'Default version', 'select', { default: '2c', choices: ['1', '2c', '3'], tab: 'polling' }),
                field('configParams.community', 'Default community', 'password', { default: 'public', tab: 'polling' }),
                field('configParams.intervalMs', 'Poll interval', 'number', { default: 60000, min: 1000, unit: 'ms', tab: 'polling' }),
                field('timeoutMs', 'Timeout', 'number', { default: 5000, min: 100, unit: 'ms', tab: 'polling' }),
                field('configParams.retries', 'Retries', 'number', { default: 0, min: 0, tab: 'polling' }),
                field('workerThreads', 'Worker threads', 'number', { default: 1, min: 1, tab: 'advanced' }),
                field('queueSize', 'Queue size', 'number', { default: 1000, min: 1, tab: 'advanced' }),
                field('configParams.targets', 'Targets', 'json', { required: true, wide: true, tab: 'targets', default: [{ name: 'router-01', host: '192.0.2.10', port: 161, version: '2c', community: 'public' }], help: 'Each target supports v1/v2c community or v3 security fields.' }),
                field('configParams.oids', 'OIDs', 'json', { required: true, wide: true, tab: 'targets', default: [{ name: 'sysUpTime', oid: '1.3.6.1.2.1.1.3.0' }], help: 'Provide at least one {name, oid} entry. A plain OID string is also accepted.' })
            ], 'Poll interval, target credentials, and OIDs are stored inside configParams.'),
            def('RabbitMqInputAdapter', 'RabbitMQ', 'move_to_inbox', 'Consume a RabbitMQ queue', ['connection', 'subscription', 'advanced'], rabbitFields(false), 'Consumes queue messages using explicit acknowledgement by default.', 'The password is stored in configParams; protect access to the configuration database and its backups.'),
            def('TlsRabbitMqInputAdapter', 'TLS RabbitMQ', 'lock', 'Consume a RabbitMQ queue over TLS', ['connection', 'subscription', 'tls', 'advanced'], rabbitFields(true), 'RabbitMQ client TLS is always enabled; the default port is 5671.'),
            def('TcpMtlsGzipInputAdapter', 'TCP mTLS + gzip', 'cell_tower', 'Castrelyx agent framed batches', ['connection', 'mtls', 'capacity', 'advanced'], [
                field('port', 'Listen port', 'number', { required: true, min: 1, max: 65535, tab: ['connection', 'capacity'] }),
                field('timeoutMs', 'Idle timeout', 'number', { required: true, default: 30000, min: 1, unit: 'ms', tab: ['connection', 'capacity'] }),
                field('queueSize', 'Queue size', 'number', { required: true, default: 10000, min: 1, tab: ['connection', 'capacity'] }),
                field('configParams.ackMode', 'Acknowledge mode', 'text', { default: 'queueAccepted', readonly: true, tab: ['connection', 'advanced'], help: 'Events are acknowledged after the in-memory queue accepts the complete batch.' }),
                field('configParams.keyStorePath', 'Key store path', 'text', { required: true, tab: ['connection', 'mtls'], help: 'Path to the PKCS12 server key store.' }),
                field('configParams.keyStorePasswordEnv', 'Key store password env', 'text', { required: true, tab: ['connection', 'mtls'], help: 'Environment variable containing the key store password.' }),
                field('configParams.trustStorePath', 'Trust store path', 'text', { required: true, tab: ['connection', 'mtls'], help: 'Path to the PKCS12 client trust store.' }),
                field('configParams.trustStorePasswordEnv', 'Trust store password env', 'text', { required: true, tab: ['connection', 'mtls'], help: 'Environment variable containing the trust store password.' }),
                field('workerThreads', 'Worker threads', 'number', { required: true, default: 32, min: 1, tab: ['connection', 'capacity'], help: 'Fallback for maximum connections.' }),
                field('configParams.maxFrameBytes', 'Maximum frame size', 'bytes', { required: true, default: 10485760, min: 1, tab: ['connection', 'capacity'] }),
                field('configParams.maxConnections', 'Maximum connections', 'number', { required: true, default: 32, min: 1, tab: ['connection', 'capacity'] }),
                field('configParams.tlsReloadIntervalMs', 'TLS reload interval', 'number', { required: true, default: 5000, min: 1, unit: 'ms', tab: ['connection', 'mtls', 'advanced'] })
            ], 'TLSv1.3 / TLSv1.2 · client auth required · PKCS12 · gzip JSON batches.'),
            def('FakeInputAdapter', 'Fake events', 'science', 'Generate a sample alert event', ['general'], [], 'Creates one Suricata-like alert event per invocation; there is no interval argument.')
        ],
        parser: [
            parserDef('JsonParser', 'JSON parser', 'data_object', 'Merge a JSON object into the event field map.'),
            parserDef('GrokParser', 'Grok parser', 'code', 'Extract named captures with a Grok pattern.', true),
            parserDef('RegexParser', 'Regex parser', 'regular_expression', 'Use capture group 1 as key and group 2 as value.', true),
            parserDef('RFC3164SyslogParser', 'RFC3164 syslog', 'terminal', 'Parse classic BSD syslog fields.'),
            parserDef('RFC5424SyslogParser', 'RFC5424 syslog', 'terminal', 'Parse versioned syslog and structured data.'),
            parserDef('HttpParser', 'HTTP parser', 'http', 'Create a headers map and body field from a raw request.')
        ],
        transform: [
            def('Filter', 'Filter events', 'filter_alt', 'Drop or pass events by exact field value', ['rules', 'advanced'], [
                field('priority', 'Order', 'number', { default: 10, min: 0, tab: 'advanced' }),
                field('filterDrop', 'Drop when any condition matches', 'keyValue', { wide: true, tab: 'rules', valueLabel: 'Comma-separated blocked values', help: 'Drop rules run first and use exact, case-sensitive matching.' }),
                field('filterPass', 'Pass only when all conditions match', 'keyValue', { wide: true, tab: 'rules', valueLabel: 'Comma-separated allowed values', help: 'Every pass field must exist and match one allowed value.' })
            ], 'Drop rules are evaluated before pass rules.'),
            def('AddProperty', 'Group fields', 'device_hub', 'Move flat fields into nested objects', ['mapping', 'advanced'], [
                field('priority', 'Order', 'number', { default: 20, min: 0, tab: 'advanced' }),
                field('addProperties', 'Target objects and source fields', 'mapList', { wide: true, required: true, tab: 'mapping', help: 'Source fields are removed from the top level and moved below the target object.' })
            ], 'Canonical type: AddProperty · existing target objects are overwritten.'),
            def('RemoveProperty', 'Remove fields', 'delete_outline', 'Remove top-level fields', ['fields', 'advanced'], [
                field('priority', 'Order', 'number', { default: 30, min: 0, tab: 'advanced' }),
                field('removeProperties', 'Fields to remove', 'jsonList', { wide: true, required: true, tab: 'fields', help: 'Comma-separated exact top-level field names. Nested paths are not supported.' })
            ], 'Removes exact top-level keys after parsing and earlier transforms.')
        ],
        output: [
            def('ConsoleOutputAdapter', 'Console', 'terminal', 'Write the final JSON to the application log', ['serialization'], [
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'serialization' })
            ], 'Shows the final console JSON serialization without external delivery.'),
            def('BenchmarkAdapter', 'Benchmark', 'speed', 'Record processing throughput', ['general'], [], 'No external delivery or JSON serialization; throughput is logged once per interval.'),
            def('TcpOutputAdapter', 'TCP', 'settings_ethernet', 'Send one JSON event per TCP connection', ['destination', 'reliability', 'advanced'], [
                field('host', 'Destination host', 'text', { required: true, tab: 'destination' }),
                field('port', 'Destination port', 'number', { required: true, min: 1, max: 65535, tab: 'destination' }),
                field('timeoutMs', 'Timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'reliability' }),
                field('retryCount', 'Retry count', 'number', { default: 3, min: 0, tab: 'reliability' }),
                field('retryDelayMs', 'Retry delay', 'number', { default: 1000, min: 1, unit: 'ms', tab: 'reliability' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Opens a connection per event · UTF-8 JSON · no delimiter or length prefix.'),
            def('HttpOutputAdapter', 'HTTP', 'http', 'Deliver JSON to an HTTP endpoint', ['destination', 'headers', 'advanced'], [
                field('url', 'Endpoint URL', 'url', { required: true, tab: 'destination' }),
                field('method', 'Method', 'select', { default: 'POST', choices: ['POST', 'PUT', 'PATCH'], tab: 'destination' }),
                field('timeoutMs', 'Timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'destination' }),
                field('headers', 'Headers', 'keyValue', { wide: true, tab: 'headers', valueLabel: 'Value', help: 'Authorization values are kept in the saved payload and masked when the backend masks them.' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Automatically adds Content-Type: application/json and User-Agent: LogParser/1.0; only 2xx is success.'),
            def('KafkaOutputAdapter', 'Kafka', 'hub', 'Produce final events to Kafka', ['destination', 'reliability', 'advanced'], [
                field('bootstrapservers', 'Bootstrap servers', 'text', { required: true, tab: 'destination' }),
                field('topicid', 'Topic', 'text', { required: true, tab: 'destination' }),
                field('key', 'Record key', 'text', { tab: 'destination' }),
                field('timeoutMs', 'Timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'reliability' }),
                field('retryCount', 'Retry count', 'number', { default: 0, min: 0, tab: 'reliability' }),
                field('retryDelayMs', 'Retry delay', 'number', { default: 250, min: 1, unit: 'ms', tab: 'reliability' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'acks=all · lz4 compression · idempotence disabled.'),
            def('OpenSearchOutputAdapter', 'OpenSearch', 'manage_search', 'Index events into OpenSearch', ['destination', 'authentication', 'advanced'], [
                field('url', 'Base URL', 'url', { required: true, tab: 'destination' }),
                field('indexTemplate', 'Index template', 'text', { required: true, tab: 'destination', help: 'Supports %{field} and Java date patterns such as %{yyMMdd}.' }),
                field('timeoutMs', 'Timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'destination' }),
                field('osUsername', 'Username', 'text', { tab: 'authentication' }),
                field('osPassword', 'Password', 'password', { tab: 'authentication' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Always sends POST {base}/{index}/_doc.', 'Current runtime trusts self-signed leaf certificates and disables hostname verification.'),
            def('RabbitMQAdapter', 'RabbitMQ', 'move_to_inbox', 'Publish to a topic exchange', ['destination', 'authentication', 'advanced'], [
                field('host', 'Host', 'text', { required: true, tab: 'destination' }),
                field('rmqPort', 'Port', 'number', { default: 5672, min: 1, max: 65535, tab: 'destination' }),
                field('exchange', 'Exchange', 'text', { required: true, tab: 'destination' }),
                field('routingkey', 'Routing key', 'text', { required: true, tab: 'destination' }),
                field('timeoutMs', 'Publisher confirm timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'destination' }),
                field('rmqUsername', 'Username', 'text', { tab: 'authentication' }),
                field('rmqPassword', 'Password', 'password', { tab: 'authentication' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Declares a TOPIC exchange before publishing.', 'TLS output is not supported. Use a trusted network boundary or another output adapter.'),
            def('MariaDbOutputAdapter', 'MariaDB', 'storage', 'Batch structured events into MariaDB', ['connection', 'batching', 'advanced'], [
                field('configParams.jdbcUrl', 'JDBC URL', 'text', { required: true, tab: 'connection' }),
                field('configParams.usernameEnv', 'Username environment variable', 'text', { required: true, tab: 'connection' }),
                field('configParams.passwordEnv', 'Password environment variable', 'text', { required: true, tab: 'connection' }),
                field('configParams.tableName', 'Table name', 'text', { default: 'castrelyx_agent_events', required: true, pattern: '^[A-Za-z0-9_]+$', tab: 'connection' }),
                field('configParams.batchSize', 'Batch size', 'number', { default: 100, min: 1, tab: 'batching' }),
                field('configParams.flushIntervalMs', 'Flush interval', 'number', { default: 5000, min: 1, unit: 'ms', tab: 'batching' }),
                field('configParams.autoCreateSchema', 'Auto-create schema', 'boolean', { default: false, tab: 'advanced' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Credentials are resolved from environment variables; batching lives inside configParams.'),
            def('ClickHouseOutputAdapter', 'ClickHouse', 'view_column', 'Write raw events and telemetry tables', ['connection', 'tables', 'buffering', 'dlq', 'advanced'], [
                field('configParams.endpointUrl', 'Endpoint URL', 'url', { required: true, tab: 'connection' }),
                field('configParams.database', 'Database', 'text', { default: 'default', pattern: '^[A-Za-z0-9_]+$', tab: 'connection' }),
                field('configParams.usernameEnv', 'Username environment variable', 'text', { tab: 'connection' }),
                field('configParams.passwordEnv', 'Password environment variable', 'text', { tab: 'connection' }),
                field('timeoutMs', 'Request timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'connection' }),
                field('configParams.tableName', 'Raw table', 'text', { required: true, default: 'castrelyx_agent_events', pattern: '^[A-Za-z0-9_]+$', tab: 'tables' }),
                field('configParams.metricTableName', 'Metric table', 'text', { default: 'manager_metric_samples', pattern: '^[A-Za-z0-9_]+$', tab: 'tables' }),
                field('configParams.stateTableName', 'State table', 'text', { default: 'manager_state_snapshots', pattern: '^[A-Za-z0-9_]+$', tab: 'tables' }),
                field('configParams.eventTableName', 'Event table', 'text', { default: 'manager_events', pattern: '^[A-Za-z0-9_]+$', tab: 'tables' }),
                field('configParams.writeTelemetryTables', 'Write telemetry tables', 'boolean', { default: true, tab: 'tables' }),
                field('configParams.batchSize', 'Batch size', 'number', { default: 100, min: 1, tab: 'buffering' }),
                field('configParams.flushIntervalMs', 'Flush interval', 'number', { default: 5000, min: 1, unit: 'ms', tab: 'buffering' }),
                field('configParams.incompleteGroupTimeoutMs', 'Incomplete group timeout', 'number', { default: 30000, min: 1, unit: 'ms', tab: 'buffering' }),
                field('configParams.maxPendingGroups', 'Maximum pending groups', 'number', { default: 2048, min: 1, tab: 'buffering' }),
                field('configParams.maxPendingItems', 'Maximum pending items', 'number', { default: 50000, min: 1, tab: 'buffering' }),
                field('configParams.maxPendingBytes', 'Maximum pending bytes', 'bytes', { default: 67108864, min: 1, tab: 'buffering' }),
                field('configParams.incompleteChunkDlqDir', 'DLQ directory', 'text', { default: '${user.home}/logparser/data/incomplete-chunks', tab: 'dlq' }),
                field('configParams.maxIncompleteChunkDlqBytes', 'Maximum DLQ bytes', 'bytes', { default: 134217728, min: 1, tab: 'dlq' }),
                field('configParams.maxIncompleteChunkDlqRecords', 'Maximum DLQ records', 'number', { default: 1000, min: 1, tab: 'dlq' }),
                field('configParams.autoCreateSchema', 'Auto-create schema', 'boolean', { default: false, tab: 'advanced' }),
                field('addOriginText', 'Include original text', 'boolean', { default: false, tab: 'advanced' })
            ], 'Structured writes, bounded buffering, and incomplete-chunk DLQ are configured independently.')
        ]
    };

    const state = {
        mounted: false,
        loading: false,
        demo: false,
        data: { input: [], parser: [], transform: [], output: [] },
        metadata: { input: [], parser: [], transform: [], output: [] },
        messageTypes: [],
        messageType: '',
        virtualMessageTypes: [],
        selected: null,
        mode: 'empty',
        draft: null,
        original: null,
        mapping: null,
        mappingOriginal: null,
        dirty: false,
        valid: true,
        activeTab: 'connection',
        sampleInput: SAMPLE_INPUT,
        testResults: new Map(),
        testRevision: 0,
        testRunId: 0,
        testRunning: false,
        lastLoadError: null,
        nextDemoId: 100
    };

    function def(type, label, icon, description, tabs, fields, notice, warning) {
        return { type, label, icon, description, tabs, fields, notice, warning };
    }

    function field(path, label, type, options = {}) {
        return { path, label, type, ...options };
    }

    function parserDef(type, label, icon, description, needsPattern = false) {
        const fields = [
            field('priority', 'Order', 'number', { default: 10, min: 0, tab: 'behavior' }),
            field('sourceField', 'Input field', 'text', { tab: 'behavior', list: 'studio-source-fields', placeholder: 'Raw event (originalText)', help: 'Optional event field to parse. Leave empty to parse the original log text. On success, the selected field is replaced with the parser result Map. You may also enter a field not shown in the list.' }),
            field('continueOnFailure', 'Continue on failure', 'boolean', { default: false, tab: 'behavior', help: 'When enabled, processing continues with the next step after this parser fails.' })
        ];
        if (needsPattern) {
            fields.unshift(field('param', type === 'GrokParser' ? 'Grok pattern' : 'Java regular expression', 'textarea', { required: true, wide: true, tab: 'pattern' }));
        }
        return def(type, label, icon, description, needsPattern ? ['pattern', 'behavior', 'advanced'] : ['behavior', 'advanced'], fields, description);
    }

    function serverTlsFields() {
        return [
            field('configParams.keyStorePath', 'Key store path', 'text', { required: true, tab: 'tls' }),
            field('configParams.keyStorePassword', 'Key store password', 'password', { tab: 'tls', help: 'Use this or the environment variable field.' }),
            field('configParams.keyStorePasswordEnv', 'Key store password env', 'text', { tab: 'tls' }),
            field('configParams.keyStoreType', 'Key store type', 'select', { default: 'PKCS12', choices: ['PKCS12', 'JKS'], tab: 'tls' }),
            field('configParams.keyPassword', 'Private key password', 'password', { tab: 'tls' }),
            field('configParams.keyPasswordEnv', 'Private key password env', 'text', { tab: 'tls' }),
            field('configParams.clientAuth', 'Client authentication', 'select', { default: 'none', choices: ['none', 'want', 'need'], tab: 'tls' }),
            field('configParams.trustStorePath', 'Trust store path', 'text', { tab: 'tls', help: 'Required when client authentication is want or need.' }),
            field('configParams.trustStorePassword', 'Trust store password', 'password', { tab: 'tls' }),
            field('configParams.trustStorePasswordEnv', 'Trust store password env', 'text', { tab: 'tls' }),
            field('configParams.trustStoreType', 'Trust store type', 'select', { default: 'PKCS12', choices: ['PKCS12', 'JKS'], tab: 'tls' }),
            field('configParams.enabledProtocols', 'Enabled protocols', 'text', { default: 'TLSv1.3,TLSv1.2', tab: 'tls', help: 'Comma-separated protocol names.' }),
            field('configParams.tlsAlgorithm', 'TLS algorithm', 'text', { default: 'TLS', tab: 'advanced' })
        ];
    }

    function rabbitFields(tls) {
        const fields = [
            field('host', 'Host', 'text', { default: 'localhost', required: true, tab: 'connection' }),
            field('port', 'Port', 'number', { default: tls ? 5671 : 5672, min: 1, max: 65535, tab: 'connection' }),
            field('configParams.username', 'Username', 'text', { default: 'guest', tab: 'connection' }),
            field('configParams.password', 'Password', 'password', { default: 'guest', tab: 'connection' }),
            field('configParams.virtualHost', 'Virtual host', 'text', { default: '/', tab: 'connection' }),
            field('timeoutMs', 'Timeout', 'number', { default: 5000, min: 100, unit: 'ms', tab: 'connection' }),
            field('configParams.charset', 'Charset', 'text', { default: 'UTF-8', tab: 'advanced' }),
            field('configParams.queue', 'Queue', 'text', { required: true, tab: 'subscription' }),
            field('configParams.autoAck', 'Auto acknowledge', 'boolean', { default: false, tab: 'subscription' }),
            field('configParams.prefetchCount', 'Prefetch count', 'number', { default: 1, min: 1, tab: 'subscription' }),
            field('configParams.declareQueue', 'Declare queue', 'boolean', { default: false, tab: 'subscription' }),
            field('configParams.durableQueue', 'Durable queue', 'boolean', { default: true, tab: 'subscription' }),
            field('configParams.exclusiveQueue', 'Exclusive queue', 'boolean', { default: false, tab: 'subscription' }),
            field('configParams.autoDeleteQueue', 'Auto-delete queue', 'boolean', { default: false, tab: 'subscription' }),
            field('configParams.exchange', 'Exchange', 'text', { tab: 'subscription' }),
            field('configParams.routingKey', 'Routing key', 'text', { default: '', tab: 'subscription' }),
            field('configParams.bindQueue', 'Bind queue', 'boolean', { default: false, tab: 'subscription' })
        ];
        if (tls) {
            fields.push(
                field('configParams.keyStorePath', 'Client key store path', 'text', { tab: 'tls' }),
                field('configParams.keyStorePassword', 'Client key store password', 'password', { tab: 'tls' }),
                field('configParams.keyStorePasswordEnv', 'Client key store password env', 'text', { tab: 'tls' }),
                field('configParams.trustStorePath', 'Trust store path', 'text', { tab: 'tls' }),
                field('configParams.trustStorePassword', 'Trust store password', 'password', { tab: 'tls' }),
                field('configParams.trustStorePasswordEnv', 'Trust store password env', 'text', { tab: 'tls' }),
                field('configParams.keyStoreType', 'Key store type', 'select', { default: 'PKCS12', choices: ['PKCS12', 'JKS'], tab: 'tls' }),
                field('configParams.trustStoreType', 'Trust store type', 'select', { default: 'PKCS12', choices: ['PKCS12', 'JKS'], tab: 'tls' }),
                field('configParams.tlsAlgorithm', 'TLS algorithm', 'text', { default: 'TLS', tab: 'tls' }),
                field('configParams.hostnameVerification', 'Hostname verification', 'boolean', { default: true, tab: 'tls' })
            );
        }
        return fields;
    }

    async function mount() {
        document.body.classList.add('pipeline-studio-active');
        bindStaticEvents();
        if (state.mounted || state.loading) {
            renderAll();
            return;
        }
        state.mounted = true;
        await loadStudioData();
    }

    function bindStaticEvents() {
        if (document.body.dataset.pipelineStudioBound === 'true') return;
        document.body.dataset.pipelineStudioBound = 'true';

        document.getElementById('studio-message-type')?.addEventListener('change', event => changeMessageType(event.target.value));
        document.getElementById('studio-new-pipeline')?.addEventListener('click', createMessageType);
        document.getElementById('studio-validate')?.addEventListener('click', () => validatePipeline(true));
        document.getElementById('studio-save-all')?.addEventListener('click', saveCurrent);
        document.getElementById('studio-deploy')?.addEventListener('click', deployPipeline);
        document.getElementById('studio-pipeline-rail')?.addEventListener('click', handleRailClick);
        document.getElementById('studio-pipeline-rail')?.addEventListener('keydown', event => {
            if (!['Enter', ' '].includes(event.key)) return;
            const node = event.target.closest('[data-node-stage]');
            if (!node || event.target.closest('input, [role="button"]')) return;
            event.preventDefault();
            selectComponent(node.dataset.nodeComponentStage || node.dataset.nodeStage, node.dataset.nodeId);
        });
        document.getElementById('studio-settings')?.addEventListener('click', handleSettingsClick);
        document.getElementById('studio-settings')?.addEventListener('input', handleFormInput);
        document.getElementById('studio-settings')?.addEventListener('change', handleFormInput);
        document.getElementById('studio-test')?.addEventListener('click', handleTestClick);
        document.getElementById('studio-test')?.addEventListener('input', handleTestInput);
        document.getElementById('studio-test')?.addEventListener('change', handleTestInput);
        window.addEventListener('beforeunload', event => {
            if (!state.dirty) return;
            event.preventDefault();
            event.returnValue = '';
        });
    }

    async function loadStudioData(options = {}) {
        state.testRunId++;
        state.loading = true;
        renderLoading();
        const forceDemo = new URLSearchParams(window.location.search).get('studioDemo') === '1';

        try {
            if (forceDemo) throw new Error('Studio demo mode requested');
            const requests = {
                input: inputAdapterAPI.getAll(),
                parser: parserAPI.getAll(),
                transform: transformAPI.getAll(),
                output: outputAdapterAPI.getAll(),
                inputTypes: metadataAPI.getInputAdapterTypes(),
                parserTypes: metadataAPI.getParserTypes(),
                transformTypes: metadataAPI.getTransformTypes(),
                outputTypes: metadataAPI.getOutputAdapterTypes()
            };
            const keys = Object.keys(requests);
            const values = await Promise.allSettled(Object.values(requests));
            const settled = Object.fromEntries(keys.map((key, index) => [key, values[index]]));
            const dataKeys = ['input', 'parser', 'transform', 'output'];
            const successfulLists = dataKeys.filter(key => settled[key].status === 'fulfilled');
            if (successfulLists.length === 0) {
                throw settled.input.reason || new Error('Configuration APIs are unavailable');
            }

            dataKeys.forEach(key => {
                state.data[key] = settled[key].status === 'fulfilled' ? unwrapList(settled[key].value) : [];
                state.metadata[key] = settled[`${key}Types`].status === 'fulfilled' ? unwrapList(settled[`${key}Types`].value) : [];
            });
            state.demo = false;
            state.lastLoadError = null;
        } catch (error) {
            state.demo = true;
            state.lastLoadError = error;
            state.data = demoData();
        }

        collectMessageTypes();
        const requested = options.messageType || state.messageType;
        state.messageType = state.messageTypes.includes(requested) ? requested : (state.messageTypes[0] || 'castrelyx-agent-item');
        if (!state.messageTypes.includes(state.messageType)) state.messageTypes.push(state.messageType);
        await loadMapping();
        chooseInitialSelection(options.selection);
        state.loading = false;
        state.dirty = false;
        renderAll();
    }

    function unwrapList(value) {
        if (Array.isArray(value)) return value;
        if (value && Array.isArray(value.content)) return value.content;
        if (value && Array.isArray(value.items)) return value.items;
        return [];
    }

    function collectMessageTypes() {
        const set = new Set(state.virtualMessageTypes);
        Object.values(state.data).flat().forEach(item => {
            if (item && item.messagetype && item.messagetype !== 'all') set.add(item.messagetype);
        });
        state.messageTypes = Array.from(set).sort((a, b) => a.localeCompare(b));
    }

    async function loadMapping() {
        if (state.demo) {
            state.mapping = demoMapping(state.messageType);
            state.mappingOriginal = deepClone(state.mapping);
            return;
        }
        try {
            const mapping = await structureAPI.getMapping(state.messageType);
            state.mapping = normalizeMapping(mapping, state.messageType);
        } catch (error) {
            if (error.status !== 404) console.warn('Structured mapping could not be loaded:', error);
            state.mapping = normalizeMapping(null, state.messageType);
        }
        state.mappingOriginal = deepClone(state.mapping);
    }

    function normalizeMapping(mapping, messageType) {
        return {
            ...(mapping || {}),
            id: mapping?.id || `${messageType}-mapping`,
            messageType,
            commonMappings: Array.isArray(mapping?.commonMappings) ? mapping.commonMappings : [],
            subTableRules: Array.isArray(mapping?.subTableRules) ? mapping.subTableRules : []
        };
    }

    function chooseInitialSelection(preferred) {
        if (preferred) {
            const found = preferred.stage === 'structured' || state.data[preferred.stage]?.some(item => String(item.id) === String(preferred.id));
            if (found) {
                selectComponent(preferred.stage, preferred.id, true);
                return;
            }
        }
        for (const stage of ['input', 'processing']) {
            const first = stageItems(stage)[0];
            if (first) {
                selectComponent(first.componentStage || stage, first.id, true);
                return;
            }
        }
        selectComponent('structured', 'mapping', true);
    }

    function stageItems(stage) {
        if (stage === 'structured') return [{ id: 'mapping', type: 'StructuredMapping' }];
        if (stage === 'processing') {
            return [
                ...stageItems('parser').map(item => ({ ...item, componentStage: 'parser' })),
                ...stageItems('transform').map(item => ({ ...item, componentStage: 'transform' }))
            ]
                .sort(compareProcessingOrder);
        }
        const items = state.data[stage] || [];
        return items
            .filter(item => stage === 'output'
                ? item.messagetype === state.messageType || item.messagetype === 'all'
                : item.messagetype === state.messageType)
            .sort((a, b) => {
                if (stage === 'parser' || stage === 'transform') {
                    const byPriority = Number(a.priority || 0) - Number(b.priority || 0);
                    if (byPriority !== 0) return byPriority;
                }
                return String(a.id).localeCompare(String(b.id), undefined, { numeric: true });
            });
    }

    function compareProcessingOrder(a, b) {
        const byPriority = Number(a.priority || 0) - Number(b.priority || 0);
        if (byPriority !== 0) return byPriority;
        const byKind = String(a.componentStage).localeCompare(String(b.componentStage));
        if (byKind !== 0) return byKind;
        if (a.id == null || b.id == null) return a.id == null ? (b.id == null ? 0 : 1) : -1;
        return String(a.id).localeCompare(String(b.id), undefined, { numeric: true });
    }

    function selectComponent(stage, id, force = false) {
        if (!force && state.dirty && !window.confirm('Discard unsaved changes and open another component?')) return false;
        state.selected = { stage, id };
        state.mode = stage === 'structured' ? 'mapping' : 'edit';
        state.activeTab = stage === 'structured' ? 'mapping' : defaultTab(stage, getSelectedEntity()?.type);
        if (stage === 'structured') {
            state.draft = null;
            state.original = null;
        } else {
            const entity = getSelectedEntity();
            state.draft = normalizeEntity(entity || {});
            state.original = deepClone(state.draft);
        }
        state.dirty = false;
        state.valid = true;
        state.testRunId++;
        renderAll();
        return true;
    }

    function getSelectedEntity() {
        if (!state.selected || state.selected.stage === 'structured') return null;
        return (state.data[state.selected.stage] || []).find(item => String(item.id) === String(state.selected.id)) || null;
    }

    function defaultTab(stage, type) {
        const typeDef = getTypeDef(stage, type);
        return typeDef?.tabs?.[0] || 'general';
    }

    function normalizeEntity(entity) {
        const copy = deepClone(entity || {});
        if (typeof copy.configParams === 'string') copy.configParams = safeJson(copy.configParams, {});
        if (!copy.configParams || typeof copy.configParams !== 'object' || Array.isArray(copy.configParams)) copy.configParams = {};
        return copy;
    }

    function serializeEntity(entity) {
        const copy = deepClone(entity);
        if (copy.configParams && typeof copy.configParams === 'object' && Object.keys(copy.configParams).length > 0) {
            copy.configParams = JSON.stringify(copy.configParams);
        } else if (copy.configParams && typeof copy.configParams === 'object') {
            delete copy.configParams;
        }
        delete copy.id;
        delete copy.createdAt;
        delete copy.updatedAt;
        delete copy.deliveryMetrics;
        return copy;
    }

    function renderLoading() {
        const rail = document.getElementById('studio-pipeline-rail');
        const settings = document.getElementById('studio-settings');
        const test = document.getElementById('studio-test');
        if (rail) rail.innerHTML = '<div class="studio-loading-editor"><div><span class="material-icons-round">sync</span>Loading pipeline…</div></div>';
        if (settings) settings.innerHTML = '<div class="studio-loading-editor"><div><span class="material-icons-round">tune</span>Loading component settings…</div></div>';
        if (test) test.innerHTML = '';
    }

    function renderAll() {
        if (!document.getElementById('view-pipeline-studio') || state.loading) return;
        renderHeader();
        renderRail();
        renderSettings();
        renderTest();
    }

    function renderHeader() {
        const select = document.getElementById('studio-message-type');
        if (select) {
            select.innerHTML = state.messageTypes.map(messageType => `<option value="${escapeAttr(messageType)}" ${messageType === state.messageType ? 'selected' : ''}>${escapeHtml(messageType)}</option>`).join('');
        }
        const draftState = document.getElementById('studio-draft-state');
        if (draftState) {
            draftState.classList.toggle('is-dirty', state.dirty && state.valid);
            draftState.classList.toggle('is-invalid', !state.valid);
            draftState.querySelector('span').textContent = state.dirty ? 'Unsaved' : 'Draft';
            draftState.querySelector('strong').textContent = !state.valid ? 'Invalid' : (state.dirty ? 'Changes' : 'Valid');
        }
        const save = document.getElementById('studio-save-all');
        if (save) save.disabled = !state.dirty;
    }

    function renderRail() {
        const rail = document.getElementById('studio-pipeline-rail');
        if (!rail) return;
        rail.innerHTML = STAGES.map(stage => {
            const items = stageItems(stage.key);
            const body = items.length
                ? items.map(item => renderNode(stage, item)).join('')
                : `<div class="studio-empty-stage">No ${escapeHtml(stage.label.toLowerCase())} configured for this message type.</div>`;
            const addButton = stage.key === 'processing'
                ? `<button class="studio-stage-add" type="button" data-add-stage="parser" aria-label="Add parser"><span class="material-icons-round">add</span>Parser</button>
                   <button class="studio-stage-add" type="button" data-add-stage="transform" aria-label="Add transform"><span class="material-icons-round">add</span>Transform</button>`
                : `<button class="studio-stage-add" type="button" data-add-stage="${stage.key}" aria-label="Add ${escapeAttr(stage.label)}">
                            <span class="material-icons-round">add</span>${stage.key === 'structured' ? 'Configure' : 'Add'}
                        </button>`;
            return `<section class="studio-stage" data-stage="${stage.key}">
                <div class="studio-stage-frame">
                    <header class="studio-stage-header">
                        <span class="studio-stage-number">${stage.number}</span>
                        <span class="studio-stage-name">${escapeHtml(stage.label)}</span>
                        <span class="studio-stage-count">${stage.key === 'structured' ? '' : items.length}</span>
                        ${addButton}
                    </header>
                    <div class="studio-node-list">${body}</div>
                </div>
            </section>`;
        }).join('');
        bindDragAndDrop();
    }

    function renderNode(stage, item) {
        const componentStage = item.componentStage || stage.key;
        const isStructured = componentStage === 'structured';
        const selected = state.selected && state.selected.stage === componentStage && (isStructured || String(state.selected.id) === String(item.id));
        const typeDef = isStructured ? null : getTypeDef(componentStage, item.type);
        const title = isStructured ? 'Event schema' : nodeTitle(componentStage, item, typeDef);
        const icon = isStructured ? 'table_chart' : (typeDef?.icon || stage.icon);
        const enabled = item.enabled !== false;
        const draggable = ['parser', 'transform'].includes(componentStage);
        const statusControl = isStructured
            ? '<span class="studio-node-always">Always runs</span>'
            : `<label class="studio-switch" title="${enabled ? 'Disable' : 'Enable'} ${escapeAttr(title)}">
                <input type="checkbox" data-toggle-stage="${componentStage}" data-toggle-id="${escapeAttr(item.id)}" ${enabled ? 'checked' : ''} aria-label="Enable ${escapeAttr(title)}">
                <span class="studio-switch-track"></span>
            </label>`;
        return `<div class="studio-node ${selected ? 'is-selected' : ''} ${enabled || isStructured ? '' : 'is-disabled'}" role="button" tabindex="0"
                    data-node-stage="${stage.key}" data-node-component-stage="${componentStage}" data-node-id="${escapeAttr(item.id)}" ${draggable ? 'draggable="true"' : ''}>
                <span class="material-icons-round studio-node-drag" aria-hidden="true">drag_indicator</span>
                <span class="material-icons-round studio-node-type-icon" aria-hidden="true">${icon}</span>
                <span class="studio-node-copy">
                    <span class="studio-node-title">${escapeHtml(title)}</span>
                    <span class="studio-node-summary">${nodeSummary(componentStage, item)}</span>
                </span>
                <span class="studio-node-actions">
                    ${statusControl}
                    <span class="studio-node-icon-button" role="button" tabindex="0" data-edit-stage="${componentStage}" data-edit-id="${escapeAttr(item.id)}" aria-label="Edit ${escapeAttr(title)}"><span class="material-icons-round">edit</span></span>
                    ${isStructured ? '' : `<span class="studio-node-icon-button" role="button" tabindex="0" data-duplicate-stage="${componentStage}" data-duplicate-id="${escapeAttr(item.id)}" aria-label="Duplicate ${escapeAttr(title)}" title="Duplicate"><span class="material-icons-round">more_vert</span></span>`}
                </span>
            </div>`;
    }

    function nodeSummary(stage, item) {
        if (stage === 'structured') {
            const common = state.mapping?.commonMappings?.length || 0;
            const rules = state.mapping?.subTableRules?.length || 0;
            return `<strong>${common} mapped · ${rules} ${rules === 1 ? 'rule' : 'rules'}</strong>`;
        }
        if (stage === 'input') {
            if (item.type === 'FileInputAdapter') return escapeHtml(item.path || 'Choose a file');
            if (item.type === 'KafkaInputAdapter') return escapeHtml(`${item.topicid || 'topic'} · ${item.bootstrapservers || 'broker'}`);
            if (item.type.includes('RabbitMq')) return escapeHtml(`${item.host || 'localhost'}:${item.port || 5672} · ${configValue(item, 'queue') || 'queue'}`);
            if (item.type === 'SnmpInputAdapter') return escapeHtml(`${(safeConfig(item).targets || []).length} targets · ${(safeConfig(item).oids || []).length} OIDs`);
            if (item.port) return escapeHtml(`:${item.port}${item.type.includes('Tls') || item.type.includes('Mtls') || item.type.includes('Https') ? ' · mTLS required' : ''}`);
            return escapeHtml(item.type);
        }
        if (stage === 'parser') return escapeHtml(`${item.type} · ${item.sourceField ? `input ${item.sourceField}` : 'raw input'} · order ${item.priority ?? 0}`);
        if (stage === 'transform') {
            if (item.type === 'Filter') return `${mapCount(item.filterDrop) + mapCount(item.filterPass)} conditions · priority ${item.priority ?? 0}`;
            if (item.type === 'AddProperty') return `${mapCount(item.addProperties)} groups · priority ${item.priority ?? 0}`;
            if (item.type === 'RemoveProperty') return `${listCount(item.removeProperties)} fields · priority ${item.priority ?? 0}`;
        }
        if (stage === 'output') {
            if (item.type === 'ConsoleOutputAdapter') return 'Application log · JSON';
            if (item.type === 'BenchmarkAdapter') return 'Throughput log · no delivery';
            if (item.type === 'ClickHouseOutputAdapter') {
                const config = safeConfig(item);
                return escapeHtml(`${config.endpointUrl || 'ClickHouse'} · ${config.tableName || 'table'}`);
            }
            if (item.type === 'MariaDbOutputAdapter') return escapeHtml(safeConfig(item).jdbcUrl || 'MariaDB');
            return escapeHtml(item.url || item.topicid || `${item.host || ''}${item.port ? `:${item.port}` : ''}` || item.type);
        }
        return '';
    }

    function nodeTitle(stage, item, typeDef) {
        if (stage === 'transform' && item.type === 'AddProperty') {
            const targets = Object.keys(safeJson(item.addProperties, {}));
            if (targets.length) return `Group ${targets[0]} fields`;
        }
        if (stage === 'transform' && item.type === 'RemoveProperty') {
            const fields = parseJsonList(item.removeProperties);
            if (fields.length) return `Remove ${fields[0]} fields`;
        }
        return typeDef?.label || humanizeType(item.type);
    }

    function renderSettings() {
        const container = document.getElementById('studio-settings');
        if (!container) return;
        if (state.mode === 'choose-type') {
            renderTypePicker(container);
            return;
        }
        if (state.mode === 'mapping') {
            renderMappingEditor(container);
            return;
        }
        if (!state.draft || !state.selected) {
            container.innerHTML = '<div class="studio-empty-editor"><div><span class="material-icons-round">account_tree</span>Select a component or add one to configure this pipeline.</div></div>';
            return;
        }
        renderEntityEditor(container);
    }

    function renderTypePicker(container) {
        const stage = state.selected?.stage;
        const label = stageLabel(stage);
        const types = allTypeDefs(stage);
        container.innerHTML = `
            <header class="studio-settings-header">
                <div>
                    <div class="studio-settings-kicker">Create ${escapeHtml(label.toLowerCase())}</div>
                    <div class="studio-settings-title-row"><h2 class="studio-settings-title">Choose a component type</h2></div>
                </div>
            </header>
            <div class="studio-form-scroll studio-type-picker">
                <input id="studio-type-search" class="studio-input studio-type-search" type="search" placeholder="Search by name or canonical type" aria-label="Search component types">
                <div class="studio-type-list">
                    ${types.map(typeDef => `<button class="studio-type-option" type="button" data-pick-type="${escapeAttr(typeDef.type)}" data-search-text="${escapeAttr(`${typeDef.label} ${typeDef.type} ${typeDef.description}`.toLowerCase())}">
                        <span class="material-icons-round">${typeDef.icon || 'extension'}</span>
                        <span><strong>${escapeHtml(typeDef.label)}</strong><small>${escapeHtml(typeDef.type)}</small><em>${escapeHtml(typeDef.description || '')}</em></span>
                    </button>`).join('')}
                </div>
            </div>
            <footer class="studio-settings-footer"><button class="studio-button studio-button-quiet" type="button" data-cancel-create>Cancel</button></footer>`;
    }

    function renderEntityEditor(container) {
        const stage = state.selected.stage;
        const typeDef = getTypeDef(stage, state.draft.type) || genericTypeDef(stage, state.draft.type);
        const action = state.mode === 'create' ? 'Create' : 'Edit';
        const tabs = typeDef.tabs?.length ? typeDef.tabs : ['general'];
        if (!tabs.includes(state.activeTab)) state.activeTab = tabs[0];
        const fields = typeDef.fields.filter(item => fieldVisibleOnTab(item, state.activeTab));
        const coreFields = [
            field('messagetype', stage === 'output' ? 'Scope' : 'Message type', stage === 'output' ? 'scope' : 'text', { readonly: stage !== 'output', required: true, help: stage === 'output' ? 'Use this pipeline or all message types.' : 'Case-sensitive pipeline connection key.' }),
            ...fields
        ];
        container.innerHTML = `
            <header class="studio-settings-header">
                <div>
                    <div class="studio-settings-kicker">${action} ${escapeHtml(stageLabel(stage).toLowerCase())}</div>
                    <div class="studio-settings-title-row">
                        <h2 class="studio-settings-title">${escapeHtml(typeDef.label)}</h2>
                        <span class="studio-settings-canonical">${escapeHtml(typeDef.type)}</span>
                    </div>
                </div>
                <label class="studio-enabled-control">
                    <span>Enabled</span>
                    <span class="studio-switch"><input type="checkbox" data-field="enabled" data-value-type="boolean" ${state.draft.enabled !== false ? 'checked' : ''}><span class="studio-switch-track"></span></span>
                </label>
            </header>
            <nav class="studio-tabs" aria-label="Settings sections">
                ${tabs.map(tab => `<button class="studio-tab ${tab === state.activeTab ? 'is-active' : ''}" type="button" data-tab="${escapeAttr(tab)}">${escapeHtml(tabLabel(tab))}</button>`).join('')}
            </nav>
            <div class="studio-form-scroll">
                ${typeDef.notice ? `<div class="studio-info-banner"><span class="material-icons-round">info</span><span>${escapeHtml(typeDef.notice)}</span></div>` : ''}
                ${typeDef.warning ? `<div class="studio-warning-banner" style="margin-top:8px"><span class="material-icons-round">warning_amber</span><span>${escapeHtml(typeDef.warning)}</span></div>` : ''}
                <div class="studio-form-grid ${coreFields.length <= 4 ? 'is-four' : ''}">${coreFields.map(renderField).join('')}</div>
            </div>
            <footer class="studio-settings-footer">
                ${state.mode === 'edit' ? `<button class="studio-button studio-button-danger" type="button" data-delete-current><span>Delete ${escapeHtml(stageLabel(stage).toLowerCase())}</span></button>` : ''}
                <button class="studio-button studio-button-quiet" type="button" data-discard-current>${state.mode === 'create' ? 'Cancel' : 'Discard changes'}</button>
                <button class="studio-button studio-button-primary" type="button" data-save-current>${state.mode === 'create' ? `Create ${escapeHtml(stageLabel(stage).toLowerCase())}` : `Save ${escapeHtml(stageLabel(stage).toLowerCase())}`}</button>
            </footer>`;
    }

    function renderField(item) {
        const value = getPath(state.draft, item.path);
        const required = item.required ? '<span class="studio-required">*</span>' : '';
        const help = item.help ? `<span class="studio-field-help">${escapeHtml(item.help)}</span>` : '';
        const wide = item.wide || ['json', 'textarea', 'keyValue', 'mapList', 'jsonList'].includes(item.type);
        const fieldClass = `studio-field ${wide ? 'is-wide' : ''}`;

        if (item.type === 'boolean') {
            return `<div class="${fieldClass}"><div class="studio-checkbox-row"><span>${escapeHtml(item.label)} ${required}</span><label class="studio-switch"><input type="checkbox" data-field="${escapeAttr(item.path)}" data-value-type="boolean" ${value === true ? 'checked' : ''}><span class="studio-switch-track"></span></label></div>${help}</div>`;
        }
        if (item.type === 'keyValue' || item.type === 'mapList') return renderKeyValueField(item, value, fieldClass, required, help);
        if (item.type === 'jsonList') {
            const values = parseJsonList(value);
            return `<div class="${fieldClass}"><label>${escapeHtml(item.label)} ${required}</label><input class="studio-input" data-field="${escapeAttr(item.path)}" data-value-type="jsonList" value="${escapeAttr(values.join(', '))}" placeholder="field_a, field_b">${help}</div>`;
        }

        let control;
        const readonly = item.readonly ? 'readonly' : '';
        const requiredAttr = item.required ? 'required' : '';
        if (item.type === 'select') {
            control = `<select class="studio-select" data-field="${escapeAttr(item.path)}" data-value-type="string" ${requiredAttr}>${(item.choices || []).map(choice => `<option value="${escapeAttr(choice)}" ${String(value ?? '') === String(choice) ? 'selected' : ''}>${escapeHtml(choice)}</option>`).join('')}</select>`;
        } else if (item.type === 'scope') {
            control = `<select class="studio-select" data-field="${escapeAttr(item.path)}" data-value-type="string"><option value="${escapeAttr(state.messageType)}" ${value !== 'all' ? 'selected' : ''}>Current · ${escapeHtml(state.messageType)}</option><option value="all" ${value === 'all' ? 'selected' : ''}>All message types</option></select>`;
        } else if (item.type === 'textarea' || item.type === 'json') {
            const display = item.type === 'json' ? JSON.stringify(value ?? item.default ?? [], null, 2) : String(value ?? '');
            control = `<textarea class="studio-textarea" data-field="${escapeAttr(item.path)}" data-value-type="${item.type}" ${requiredAttr} ${readonly}>${escapeHtml(display)}</textarea>`;
        } else {
            const inputType = item.type === 'password' ? 'password' : (item.type === 'url' ? 'url' : (item.type === 'number' || item.type === 'bytes' ? 'number' : 'text'));
            const numberAttrs = ['number', 'bytes'].includes(item.type) ? `${item.min != null ? `min="${item.min}"` : ''} ${item.max != null ? `max="${item.max}"` : ''}` : '';
            const unit = item.type === 'bytes' ? humanBytes(Number(value ?? item.default ?? 0)) : item.unit;
            const listAttr = item.list ? `list="${escapeAttr(item.list)}"` : '';
            const placeholder = item.placeholder ? `placeholder="${escapeAttr(item.placeholder)}"` : '';
            const sourceDatalist = item.path === 'sourceField' ? `<datalist id="studio-source-fields"><option value="">Raw event (originalText)</option>${availableSourceFields().map(source => `<option value="${escapeAttr(source)}"></option>`).join('')}</datalist>` : '';
            control = `<span class="studio-input-wrap"><input class="studio-input ${unit ? 'has-unit' : ''}" type="${inputType}" data-field="${escapeAttr(item.path)}" data-value-type="${['number', 'bytes'].includes(item.type) ? 'number' : 'string'}" value="${escapeAttr(value ?? '')}" ${numberAttrs} ${listAttr} ${placeholder} ${requiredAttr} ${readonly}>${unit ? `<span class="studio-input-unit">${escapeHtml(unit)}</span>` : ''}</span>${sourceDatalist}`;
        }
        return `<div class="${fieldClass}" data-field-shell="${escapeAttr(item.path)}"><label>${escapeHtml(item.label)} ${required}</label>${control}${help}</div>`;
    }

    function renderKeyValueField(item, value, fieldClass, required, help) {
        const map = safeJson(value, {});
        const entries = Object.entries(map || {});
        const rows = entries.length ? entries : [['', item.type === 'mapList' ? [] : '']];
        return `<div class="${fieldClass}" data-map-field="${escapeAttr(item.path)}" data-map-type="${item.type}">
            <label>${escapeHtml(item.label)} ${required}</label>
            <div class="studio-row-editor">
                <div class="studio-row-editor-header"><span>${item.type === 'mapList' ? 'Target object' : 'Field / Key'}</span><span>${escapeHtml(item.valueLabel || (item.type === 'mapList' ? 'Source fields' : 'Values'))}</span><span></span><span></span></div>
                ${rows.map(([key, entryValue], index) => `<div class="studio-row-editor-row" data-map-row>
                    <input class="studio-input" data-map-key value="${escapeAttr(key)}" aria-label="Key ${index + 1}">
                    <input class="studio-input" data-map-value value="${escapeAttr(Array.isArray(entryValue) ? entryValue.join(', ') : entryValue)}" aria-label="Value ${index + 1}">
                    <span class="studio-field-help">${item.type === 'mapList' ? 'comma-separated' : 'exact values'}</span>
                    <button class="studio-row-delete" type="button" data-remove-map-row aria-label="Remove row"><span class="material-icons-round">close</span></button>
                </div>`).join('')}
                <button class="studio-row-add" type="button" data-add-map-row>Add row</button>
            </div>${help}
        </div>`;
    }

    function renderMappingEditor(container) {
        const mapping = state.mapping || normalizeMapping(null, state.messageType);
        const tabs = ['mapping', 'rules', 'advanced'];
        if (!tabs.includes(state.activeTab)) state.activeTab = 'mapping';
        let content = '';
        if (state.activeTab === 'mapping') content = renderCommonMappings(mapping);
        else if (state.activeTab === 'rules') content = renderRules(mapping);
        else content = `<div class="studio-info-banner"><span class="material-icons-round">info</span><span>Raw payload is read-only. event_id, ingest_time, and raw_log are runtime-managed fields and are not available as common targets.</span></div><textarea class="studio-textarea" style="min-height:260px;margin-top:9px" readonly>${escapeHtml(JSON.stringify(mapping, null, 2))}</textarea>`;
        container.innerHTML = `
            <header class="studio-settings-header">
                <div><div class="studio-settings-kicker">Edit structured transform</div><div class="studio-settings-title-row"><h2 class="studio-settings-title">Event schema</h2><span class="studio-settings-canonical">Structured mapping</span></div></div>
                <div class="studio-enabled-control"><span>Always runs</span></div>
            </header>
            <nav class="studio-tabs" aria-label="Mapping sections">${tabs.map(tab => `<button class="studio-tab ${tab === state.activeTab ? 'is-active' : ''}" type="button" data-tab="${tab}">${tabLabel(tab)}</button>`).join('')}</nav>
            <div class="studio-form-scroll"><div class="studio-info-banner"><span class="material-icons-round">info</span><span>First matching rule wins · unmapped source fields remain in additionalAttributes.</span></div>${content}</div>
            <footer class="studio-settings-footer"><button class="studio-button studio-button-quiet" type="button" data-reset-mapping>Reset draft</button><button class="studio-button studio-button-primary" type="button" data-save-mapping>Save mapping</button></footer>`;
    }

    function renderCommonMappings(mapping) {
        const rows = mapping.commonMappings.length ? mapping.commonMappings : [{ sourceField: '', targetField: '', defaultValue: null }];
        return `<div class="studio-mapping-workspace">
            <div class="studio-mapping-toolbar"><div class="studio-field"><label>Mapping ID</label><input class="studio-input" id="studio-mapping-id" value="${escapeAttr(mapping.id || '')}"></div><div class="studio-field"><label>Current pipeline</label><input class="studio-input" value="${escapeAttr(state.messageType)}" readonly></div><button class="studio-button studio-button-secondary" type="button" data-auto-map>Auto map</button></div>
            <div class="studio-row-editor" id="studio-common-mappings">
                <div class="studio-row-editor-header"><span>Source field</span><span>Target field</span><span>Default value</span><span></span></div>
                ${rows.map((row, index) => `<div class="studio-row-editor-row" data-mapping-row>
                    <input class="studio-input" data-mapping-source value="${escapeAttr(row.sourceField || '')}" aria-label="Source field ${index + 1}">
                    <select class="studio-select" data-mapping-target aria-label="Target field ${index + 1}"><option value="">Select target</option>${COMMON_TARGETS.map(target => `<option value="${target}" ${row.targetField === target ? 'selected' : ''}>${target}</option>`).join('')}</select>
                    <input class="studio-input" data-mapping-default value="${escapeAttr(row.defaultValue ?? '')}" aria-label="Default value ${index + 1}">
                    <button class="studio-row-delete" type="button" data-remove-mapping-row aria-label="Remove mapping"><span class="material-icons-round">close</span></button>
                </div>`).join('')}
                <button class="studio-row-add" type="button" data-add-mapping-row>Add mapping</button>
            </div>
        </div>`;
    }

    function renderRules(mapping) {
        const rules = mapping.subTableRules.length ? mapping.subTableRules : [{ targetSubTable: 'event_network', conditionExpression: '', mappings: [] }];
        return `<div class="studio-mapping-workspace" id="studio-rule-list">${rules.map((rule, index) => `<article class="studio-rule-card" data-rule-card>
            <header><strong>Rule ${index + 1}</strong><button class="studio-row-delete" type="button" data-remove-rule aria-label="Remove rule"><span class="material-icons-round">delete_outline</span></button></header>
            <div class="studio-form-grid">
                <div class="studio-field"><label>Target sub-table</label><select class="studio-select" data-rule-target><option ${rule.targetSubTable === 'event_network' ? 'selected' : ''}>event_network</option><option ${rule.targetSubTable === 'event_web' ? 'selected' : ''}>event_web</option><option ${rule.targetSubTable === 'event_auth' ? 'selected' : ''}>event_auth</option></select></div>
                <div class="studio-field"><label>SpEL condition</label><input class="studio-input" data-rule-condition value="${escapeAttr(rule.conditionExpression || '')}" placeholder="dst_port == 443"></div>
                <div class="studio-field is-wide"><label>Rule mappings</label><textarea class="studio-textarea" data-rule-mappings>${escapeHtml(JSON.stringify(rule.mappings || [], null, 2))}</textarea><span class="studio-field-help">Array of {sourceField, targetField, defaultValue} mappings.</span></div>
            </div>
        </article>`).join('')}<button class="studio-row-add" style="border:1px solid var(--studio-border);margin-top:8px" type="button" data-add-rule>Add rule</button></div>`;
    }

    function renderTest() {
        const container = document.getElementById('studio-test');
        if (!container) return;
        const { node, source, result } = testContext();
        const sourceLabel = source.node ? `Source · ${source.node.label} test result` : 'Sample batch item';
        const status = result?.status || source.error || 'Run test to see the current draft result';
        const activeInput = document.activeElement;
        const selection = activeInput?.id === 'studio-sample-input'
            ? [activeInput.selectionStart, activeInput.selectionEnd] : null;
        container.innerHTML = `
            <header class="studio-test-toolbar">
                <div><h3 class="studio-test-title">Test current draft</h3><p class="studio-test-subtitle">Runs in memory · Nothing is deployed${state.demo ? ' · Demo data' : ''}</p></div>
                <div class="studio-test-controls">
                    <span class="studio-test-stats">${escapeHtml(result?.stats || '—')}</span>
                    <button class="studio-run-button" type="button" data-run-test ${state.testRunning || !node || source.error ? 'disabled' : ''}><span class="material-icons-round">${state.testRunning ? 'sync' : 'play_arrow'}</span>${state.testRunning ? 'Running' : 'Run test'}</button>
                </div>
            </header>
            <div class="studio-test-grid">
                <section class="studio-code-pane">
                    <div class="studio-code-heading"><span>${escapeHtml(sourceLabel)}</span><span class="material-icons-round">${source.node ? 'link' : 'edit'}</span></div>
                    <textarea id="${source.node ? 'studio-test-source' : 'studio-sample-input'}" aria-label="${escapeAttr(sourceLabel)}" class="studio-code-editor" spellcheck="false" ${source.node ? 'readonly' : ''}>${escapeHtml(source.text)}</textarea>
                    <div class="studio-code-status ${source.error ? 'is-error' : ''}"><span class="material-icons-round">${source.error ? 'info' : 'check_circle'}</span><span>${escapeHtml(source.error || (source.node ? 'Inherited test result · read only' : 'Draft sample · editable JSON or raw text'))}</span></div>
                </section>
                <section class="studio-code-pane">
                    <div class="studio-code-heading"><span>Result after ${escapeHtml(node?.label || 'current draft')}</span><span class="material-icons-round">content_copy</span></div>
                    <pre id="studio-test-result" class="studio-code-result">${result ? syntaxHighlight(result.payload) : 'No test result yet'}</pre>
                    <div class="studio-code-status ${result?.error || source.error ? 'is-error' : ''}"><span class="material-icons-round">${result?.error || source.error ? 'error' : 'info'}</span><span>${escapeHtml(status)}</span></div>
                </section>
            </div>`;
        if (selection) {
            const input = document.getElementById('studio-sample-input');
            input?.focus();
            input?.setSelectionRange(...selection);
        }
    }

    function componentStageKey(stage, id) {
        return stage === 'structured' ? 'structured:mapping' : `${stage}:${id ?? 'draft'}`;
    }

    function testNodes() {
        const nodesFor = stage => {
            let items = stageItems(stage).map(normalizeEntity);
            if (state.selected?.stage === stage && state.draft && ['create', 'edit'].includes(state.mode)) {
                items = items.filter(item => String(item.id) !== String(state.selected.id));
                items.push({ ...deepClone(state.draft), id: state.selected.id });
            }
            return items.map(config => ({ stage, id: config.id, config }));
        };
        const processing = [...nodesFor('parser'), ...nodesFor('transform')].sort((a, b) =>
            compareProcessingOrder({ ...a.config, componentStage: a.stage }, { ...b.config, componentStage: b.stage }));
        const nodes = [
            ...nodesFor('input'), ...processing,
            { stage: 'structured', id: 'mapping', config: state.mapping }, ...nodesFor('output')
        ];
        let previous = null;
        for (const node of nodes) {
            node.key = componentStageKey(node.stage, node.id);
            node.label = node.stage === 'structured' ? 'Structured transform'
                : (getTypeDef(node.stage, node.config.type)?.label || node.config.type);
            if (['parser', 'transform'].includes(node.stage)) node.label += ` · order ${node.config.priority ?? 0}`;
            node.sourceKey = node.stage === 'input' ? null : previous;
            if (node.stage === 'structured' || (['parser', 'transform'].includes(node.stage) && node.config.enabled !== false)) previous = node.key;
        }
        return nodes;
    }

    function testSignature(node) {
        // Compare the editable test settings, not server-generated ids/timestamps/default null fields.
        const definition = getTypeDef(node.stage, node.config?.type);
        const config = definition ? [node.config.type, node.config.enabled !== false,
            ...definition.fields.map(item => {
                let value = getPath(node.config, item.path);
                if (value == null || value === '') value = item.default ?? null;
                if (['keyValue', 'mapList'].includes(item.type)) value = safeJson(value, {});
                if (item.type === 'json') value = safeJson(value, value);
                if (item.type === 'jsonList') value = parseJsonList(value);
                return [item.path, value];
            })]
            : node.config;
        return JSON.stringify([state.messageType, state.sampleInput, state.demo, config,
            node.sourceKey, state.testResults.get(node.sourceKey)?.revision ?? null]);
    }

    function pruneTestResults(nodes) {
        const keys = new Set(nodes.map(node => node.key));
        for (const key of state.testResults.keys()) if (!keys.has(key)) state.testResults.delete(key);
        // Nodes are visited in execution order so invalidating a result also invalidates its descendants.
        for (const node of nodes) {
            const result = state.testResults.get(node.key);
            const source = state.testResults.get(node.sourceKey);
            if (result && (result.signature !== testSignature(node)
                || (node.sourceKey && (!source || source.error || source.count === 0)))) state.testResults.delete(node.key);
        }
    }

    function testSource(node, nodes) {
        if (!node?.sourceKey) return { node: null, text: state.sampleInput, error: node ? null : 'Choose a component type to test.' };
        const sourceNode = nodes.find(item => item.key === node.sourceKey);
        const result = state.testResults.get(node.sourceKey);
        let error = null;
        if (!result || result.error) error = `Test ${sourceNode.label} successfully first.`;
        else if (result.count === 0) error = `${sourceNode.label} dropped the event. No source is available.`;
        return { node: sourceNode, text: error ? '' : JSON.stringify(result.payload, null, 2),
            payload: error ? null : deepClone(result.payload), error };
    }

    function testContext() {
        const nodes = testNodes();
        pruneTestResults(nodes);
        const key = state.selected && componentStageKey(state.selected.stage, state.selected.id);
        const node = nodes.find(item => item.key === key);
        return { nodes, node, source: testSource(node, nodes), result: state.testResults.get(key) };
    }

    function inputPreview(parsed, input) {
        const base = {
            origin: {
                remote_ip: '10.0.0.45',
                remote_port: 53421,
            },
            message_type: state.messageType,
            received_at: new Date().toISOString(),
            payload: parsed
        };
        if (!input) return base;
        const config = safeConfig(input);
        if (input.type === 'TcpMtlsGzipInputAdapter' || input.type === 'TlsTcpInputAdapter' || input.type === 'HttpsInputAdapter') {
            base.origin.tls_version = 'TLSv1.3';
            base.origin.client_verified = input.type === 'TcpMtlsGzipInputAdapter' || config.clientAuth === 'need';
        }
        if (input.type === 'FileInputAdapter') {
            delete base.origin;
            base.source_id = input.path;
            base.preview_checks = { exists: 'requires input test API', regular_file: 'requires input test API', encoding: 'UTF-8' };
        } else if (input.type === 'KafkaInputAdapter') {
            base.source_id = `${input.bootstrapservers || 'broker'}/${input.topicid || 'topic'}`;
            base.preview_checks = { broker_connection: 'not attempted', topic_metadata: 'requires input test API', consume_permission: 'requires input test API' };
        } else if (input.type === 'SnmpInputAdapter') {
            base.source_id = 'snmp-poll';
            base.preview_checks = { targets: (config.targets || []).length, oids: (config.oids || []).length, poll_request: 'not attempted' };
        } else if (input.type.includes('RabbitMq')) {
            base.source_id = `amqp://${input.host || 'localhost'}:${input.port || 5672}/${config.queue || 'queue'}`;
            base.preview_checks = { connection: 'not attempted', queue: config.queue || null };
        } else if (input.type === 'FakeInputAdapter') {
            delete base.origin;
            base.source_id = 'fake://suricata-alert';
        } else {
            base.source_id = `${input.type.startsWith('Udp') ? 'udp' : input.type.startsWith('Http') || input.type.startsWith('Https') ? 'http' : 'tcp'}://10.0.0.45:${input.port || 0}`;
        }
        return base;
    }

    function handleRailClick(event) {
        const add = event.target.closest('[data-add-stage]');
        if (add) {
            beginCreate(add.dataset.addStage);
            return;
        }
        const toggle = event.target.closest('[data-toggle-stage]');
        if (toggle) {
            event.stopPropagation();
            toggleFromRail(toggle.dataset.toggleStage, toggle.dataset.toggleId, toggle.checked);
            return;
        }
        const duplicate = event.target.closest('[data-duplicate-stage]');
        if (duplicate) {
            event.stopPropagation();
            duplicateComponent(duplicate.dataset.duplicateStage, duplicate.dataset.duplicateId);
            return;
        }
        const edit = event.target.closest('[data-edit-stage]');
        if (edit) {
            event.stopPropagation();
            selectComponent(edit.dataset.editStage, edit.dataset.editId);
            return;
        }
        const node = event.target.closest('[data-node-stage]');
        if (node) selectComponent(node.dataset.nodeComponentStage || node.dataset.nodeStage, node.dataset.nodeId);
    }

    function handleSettingsClick(event) {
        const tab = event.target.closest('[data-tab]');
        if (tab) {
            syncDraftFromForm();
            if (state.mode === 'mapping') syncMappingFromForm();
            state.activeTab = tab.dataset.tab;
            renderSettings();
            return;
        }
        const picked = event.target.closest('[data-pick-type]');
        if (picked) {
            startCreateWithType(picked.dataset.pickType);
            return;
        }
        if (event.target.closest('[data-cancel-create]')) {
            chooseInitialSelection();
            return;
        }
        if (event.target.closest('[data-save-current]')) {
            saveCurrent();
            return;
        }
        if (event.target.closest('[data-delete-current]')) {
            deleteCurrent();
            return;
        }
        if (event.target.closest('[data-discard-current]')) {
            discardCurrent();
            return;
        }
        const addMapRow = event.target.closest('[data-add-map-row]');
        if (addMapRow) {
            syncDraftFromForm();
            const path = addMapRow.closest('[data-map-field]').dataset.mapField;
            const map = safeJson(getPath(state.draft, path), {});
            map[`field_${Object.keys(map).length + 1}`] = addMapRow.closest('[data-map-field]').dataset.mapType === 'mapList' ? [] : '';
            setPath(state.draft, path, JSON.stringify(map));
            markDirty();
            renderSettings();
            return;
        }
        const removeMapRow = event.target.closest('[data-remove-map-row]');
        if (removeMapRow) {
            removeMapRow.closest('[data-map-row]').remove();
            syncDraftFromForm();
            markDirty();
            return;
        }
        if (event.target.closest('[data-add-mapping-row]')) {
            syncMappingFromForm();
            state.mapping.commonMappings.push({ sourceField: '', targetField: '', defaultValue: null });
            markDirty();
            renderSettings();
            return;
        }
        const removeMapping = event.target.closest('[data-remove-mapping-row]');
        if (removeMapping) {
            removeMapping.closest('[data-mapping-row]').remove();
            syncMappingFromForm();
            markDirty();
            return;
        }
        if (event.target.closest('[data-auto-map]')) {
            autoMap();
            return;
        }
        if (event.target.closest('[data-save-mapping]')) {
            saveMapping();
            return;
        }
        if (event.target.closest('[data-reset-mapping]')) {
            state.mapping = deepClone(state.mappingOriginal);
            state.dirty = false;
            renderAll();
            return;
        }
        if (event.target.closest('[data-add-rule]')) {
            syncMappingFromForm();
            state.mapping.subTableRules.push({ targetSubTable: 'event_network', conditionExpression: '', mappings: [] });
            markDirty();
            renderSettings();
            return;
        }
        const removeRule = event.target.closest('[data-remove-rule]');
        if (removeRule) {
            removeRule.closest('[data-rule-card]').remove();
            syncMappingFromForm();
            markDirty();
        }
    }

    function handleFormInput(event) {
        if (event.target.id === 'studio-type-search') {
            const query = event.target.value.trim().toLowerCase();
            document.querySelectorAll('.studio-type-option').forEach(option => {
                option.hidden = query && !option.dataset.searchText.includes(query);
            });
            return;
        }
        if (event.target.matches('[data-field], [data-map-key], [data-map-value], #studio-mapping-id, [data-mapping-source], [data-mapping-target], [data-mapping-default], [data-rule-target], [data-rule-condition], [data-rule-mappings]')) {
            if (state.mode === 'mapping') syncMappingFromForm();
            else syncDraftFromForm();
            markDirty();
            if (event.target.dataset.valueType === 'number' && event.target.closest('.studio-input-wrap')?.querySelector('.studio-input-unit') && event.target.dataset.field?.toLowerCase().includes('bytes')) {
                event.target.closest('.studio-input-wrap').querySelector('.studio-input-unit').textContent = humanBytes(Number(event.target.value || 0));
            }
        }
    }

    function handleTestInput(event) {
        if (event.target.id === 'studio-sample-input' && state.sampleInput !== event.target.value) {
            state.sampleInput = event.target.value;
            state.testRunId++;
            state.testResults.clear();
            renderTest();
        }
    }

    function handleTestClick(event) {
        if (event.target.closest('[data-run-test]')) runTest();
        if (event.target.closest('.studio-code-heading .material-icons-round') && event.target.textContent.trim() === 'content_copy') {
            const result = testContext().result;
            if (!result) return;
            navigator.clipboard?.writeText(JSON.stringify(result.payload, null, 2));
            showToast('Test result copied', 'success');
        }
    }

    function beginCreate(stage) {
        if (stage === 'structured') {
            selectComponent('structured', 'mapping');
            return;
        }
        if (state.dirty && !window.confirm('Discard unsaved changes and create a new component?')) return;
        state.selected = { stage, id: null };
        state.mode = 'choose-type';
        state.draft = null;
        state.original = null;
        state.dirty = false;
        state.testRunId++;
        renderAll();
    }

    function startCreateWithType(type) {
        const stage = state.selected.stage;
        const typeDef = getTypeDef(stage, type) || genericTypeDef(stage, type);
        const draft = { type, messagetype: state.messageType, enabled: true, configParams: {} };
        typeDef.fields.forEach(item => {
            if (item.default !== undefined) setPath(draft, item.path, deepClone(item.default));
        });
        if (stage === 'parser' || stage === 'transform') {
            const priorities = stageItems('processing').map(item => Number(item.priority || 0));
            draft.priority = (priorities.length ? Math.max(...priorities) : 0) + 10;
        }
        state.mode = 'create';
        state.draft = draft;
        state.original = deepClone(draft);
        state.activeTab = typeDef.tabs?.[0] || 'general';
        state.dirty = true;
        state.testRunId++;
        renderAll();
    }

    function syncDraftFromForm() {
        if (!state.draft || state.mode === 'mapping' || state.mode === 'choose-type') return;
        document.querySelectorAll('#studio-settings [data-field]').forEach(input => {
            const path = input.dataset.field;
            const type = input.dataset.valueType || 'string';
            let value;
            if (type === 'boolean') value = input.checked;
            else if (type === 'number') value = input.value === '' ? null : Number(input.value);
            else if (type === 'json') value = safeJson(input.value, input.value);
            else if (type === 'jsonList') value = JSON.stringify(input.value.split(',').map(entry => entry.trim()).filter(Boolean));
            else value = input.value;
            setPath(state.draft, path, value);
        });
        document.querySelectorAll('#studio-settings [data-map-field]').forEach(group => {
            const map = {};
            group.querySelectorAll('[data-map-row]').forEach(row => {
                const key = row.querySelector('[data-map-key]').value.trim();
                if (!key) return;
                const raw = row.querySelector('[data-map-value]').value;
                map[key] = group.dataset.mapType === 'mapList' ? raw.split(',').map(value => value.trim()).filter(Boolean) : raw;
            });
            setPath(state.draft, group.dataset.mapField, JSON.stringify(map));
        });
    }

    function syncMappingFromForm() {
        if (state.mode !== 'mapping' || !state.mapping) return;
        const id = document.getElementById('studio-mapping-id');
        if (id) state.mapping.id = id.value.trim();
        const mappingRows = document.querySelectorAll('#studio-settings [data-mapping-row]');
        if (document.getElementById('studio-common-mappings')) {
            state.mapping.commonMappings = Array.from(mappingRows).map(row => ({
                sourceField: row.querySelector('[data-mapping-source]').value.trim(),
                targetField: row.querySelector('[data-mapping-target]').value,
                defaultValue: row.querySelector('[data-mapping-default]').value || null
            })).filter(row => row.sourceField || row.targetField);
        }
        const ruleCards = document.querySelectorAll('#studio-settings [data-rule-card]');
        if (document.getElementById('studio-rule-list')) {
            state.mapping.subTableRules = Array.from(ruleCards).map(card => ({
                targetSubTable: card.querySelector('[data-rule-target]').value,
                conditionExpression: card.querySelector('[data-rule-condition]').value || null,
                mappings: safeJson(card.querySelector('[data-rule-mappings]').value, [])
            }));
        }
        state.mapping.messageType = state.messageType;
    }

    function markDirty() {
        state.dirty = true;
        state.valid = true;
        state.testRunId++;
        renderHeader();
        renderTest();
    }

    async function saveCurrent() {
        if (state.mode === 'mapping') return saveMapping();
        if (!state.draft || !state.selected || state.mode === 'choose-type') return;
        syncDraftFromForm();
        const errors = validateDraft(state.selected.stage, state.draft);
        if (errors.length) {
            state.valid = false;
            renderHeader();
            showToast(errors[0], 'error');
            focusInvalidField(errors[0].path);
            return;
        }

        const stage = state.selected.stage;
        const payload = serializeEntity(state.draft);
        try {
            let saved;
            if (state.demo) {
                saved = { ...deepClone(payload), id: state.mode === 'create' ? state.nextDemoId++ : state.selected.id };
                const list = state.data[stage];
                const existingIndex = list.findIndex(item => String(item.id) === String(saved.id));
                if (existingIndex >= 0) list[existingIndex] = saved;
                else list.push(saved);
            } else {
                const adapterApi = apiForStage(stage);
                saved = state.mode === 'create' ? await adapterApi.create(payload) : await adapterApi.update(state.selected.id, payload);
                if (!saved) saved = { ...payload, id: state.selected.id };
            }
            const selection = { stage, id: saved.id ?? state.selected.id };
            const draftKey = componentStageKey(stage, state.selected.id);
            const savedKey = componentStageKey(stage, selection.id);
            if (draftKey !== savedKey && state.testResults.has(draftKey)) {
                state.testResults.set(savedKey, state.testResults.get(draftKey));
                state.testResults.delete(draftKey);
            }
            showToast(`${stageLabel(stage)} saved`, 'success');
            if (state.demo) {
                collectMessageTypes();
                selectComponent(selection.stage, selection.id, true);
            } else {
                await loadStudioData({ messageType: state.messageType, selection });
            }
        } catch (error) {
            state.valid = false;
            renderHeader();
            showToast(error.message || 'Save failed', 'error');
        }
    }

    async function saveMapping() {
        syncMappingFromForm();
        const errors = validateMapping(state.mapping);
        if (errors.length) {
            state.valid = false;
            renderHeader();
            showToast(errors[0], 'error');
            return;
        }
        try {
            if (!state.demo) await structureAPI.saveMapping(state.mapping);
            state.mappingOriginal = deepClone(state.mapping);
            state.dirty = false;
            state.valid = true;
            showToast('Structured mapping saved', 'success');
            renderAll();
        } catch (error) {
            showToast(error.message || 'Mapping save failed', 'error');
        }
    }

    function validateDraft(stage, draft) {
        const errors = [];
        if (!draft.type) errors.push({ path: 'type', message: 'Component type is required.' });
        if (!draft.messagetype) errors.push({ path: 'messagetype', message: 'Message type is required.' });
        const typeDef = getTypeDef(stage, draft.type);
        (typeDef?.fields || []).forEach(item => {
            const value = getPath(draft, item.path);
            if (item.required && isBlank(value)) errors.push({ path: item.path, message: `${item.label} is required.` });
            if (value != null && value !== '' && item.min != null && Number(value) < item.min) errors.push({ path: item.path, message: `${item.label} must be at least ${item.min}.` });
            if (value != null && value !== '' && item.max != null && Number(value) > item.max) errors.push({ path: item.path, message: `${item.label} must be no more than ${item.max}.` });
            if (value && item.pattern && !(new RegExp(item.pattern).test(String(value)))) errors.push({ path: item.path, message: `${item.label} contains unsupported characters.` });
        });
        if (draft.type === 'Filter' && mapCount(draft.filterDrop) + mapCount(draft.filterPass) === 0) errors.push({ path: 'filterDrop', message: 'Add at least one drop or pass condition.' });
        if (draft.type === 'TlsTcpInputAdapter' || draft.type === 'HttpsInputAdapter') {
            const config = draft.configParams || {};
            if (!config.keyStorePassword && !config.keyStorePasswordEnv) errors.push({ path: 'configParams.keyStorePasswordEnv', message: 'Provide a key store password or environment variable.' });
            if (['want', 'need'].includes(config.clientAuth) && (!config.trustStorePath || (!config.trustStorePassword && !config.trustStorePasswordEnv))) errors.push({ path: 'configParams.trustStorePath', message: 'Client authentication requires a trust store path and password source.' });
        }
        if (draft.type === 'ClickHouseOutputAdapter') {
            const config = draft.configParams || {};
            if (Boolean(config.usernameEnv) !== Boolean(config.passwordEnv)) errors.push({ path: 'configParams.usernameEnv', message: 'ClickHouse username and password environment variables must be set together.' });
        }
        return errors;
    }

    function validateMapping(mapping) {
        if (!mapping) return ['Mapping configuration is missing.'];
        const targets = mapping.commonMappings.map(row => row.targetField).filter(Boolean);
        const duplicate = targets.find((target, index) => targets.indexOf(target) !== index);
        if (duplicate) return [`Common target ${duplicate} is mapped more than once.`];
        return [];
    }

    function focusInvalidField(path) {
        const input = document.querySelector(`#studio-settings [data-field="${cssEscape(path)}"]`);
        if (!input) return;
        input.closest('.studio-field')?.classList.add('has-error');
        input.focus();
    }

    function discardCurrent() {
        if (state.mode === 'create') {
            chooseInitialSelection();
            return;
        }
        state.draft = deepClone(state.original);
        state.dirty = false;
        state.valid = true;
        renderAll();
    }

    async function deleteCurrent() {
        if (!state.selected || state.selected.stage === 'structured') return;
        const stage = state.selected.stage;
        const entity = getSelectedEntity();
        const typeDef = getTypeDef(stage, entity?.type);
        const warning = stageItems(stage).filter(item => item.enabled !== false).length <= 1 && ['input', 'output'].includes(stage)
            ? '\n\nThis is the last active component in its stage, so the pipeline will not be deployable.' : '';
        if (!window.confirm(`Delete ${typeDef?.label || entity?.type || stageLabel(stage)}?${warning}`)) return;
        try {
            if (state.demo) state.data[stage] = state.data[stage].filter(item => String(item.id) !== String(state.selected.id));
            else await apiForStage(stage).delete(state.selected.id);
            showToast(`${stageLabel(stage)} deleted`, 'success');
            state.dirty = false;
            if (state.demo) chooseInitialSelection();
            else await loadStudioData({ messageType: state.messageType });
        } catch (error) {
            showToast(error.message || 'Delete failed', 'error');
        }
    }

    async function toggleFromRail(stage, id, enabled) {
        const entity = state.data[stage]?.find(item => String(item.id) === String(id));
        if (!entity) return;
        try {
            if (!state.demo) await (enabled ? apiForStage(stage).enable(id) : apiForStage(stage).disable(id));
            entity.enabled = enabled;
            if (state.selected?.stage === stage && String(state.selected.id) === String(id)) {
                state.draft.enabled = enabled;
                state.original.enabled = enabled;
            }
            showToast(`${enabled ? 'Enabled' : 'Disabled'} ${getTypeDef(stage, entity.type)?.label || entity.type}`, 'success');
            renderAll();
        } catch (error) {
            entity.enabled = !enabled;
            showToast(error.message || 'Status update failed', 'error');
            renderAll();
        }
    }

    async function duplicateComponent(stage, id) {
        const entity = state.data[stage]?.find(item => String(item.id) === String(id));
        if (!entity || !window.confirm(`Duplicate ${getTypeDef(stage, entity.type)?.label || entity.type} in ${state.messageType}?`)) return;
        const payload = serializeEntity(normalizeEntity(entity));
        if (stage === 'parser' || stage === 'transform') payload.priority = Number(entity.priority || 0) + 1;
        try {
            let created;
            if (state.demo) {
                created = { ...payload, id: state.nextDemoId++ };
                state.data[stage].push(created);
            } else created = await apiForStage(stage).create(payload);
            showToast('Component duplicated', 'success');
            if (state.demo) selectComponent(stage, created.id, true);
            else await loadStudioData({ messageType: state.messageType, selection: { stage, id: created.id } });
        } catch (error) {
            showToast(error.message || 'Duplicate failed', 'error');
        }
    }

    function bindDragAndDrop() {
        document.querySelectorAll('.studio-node[draggable="true"]').forEach(node => {
            node.addEventListener('dragstart', event => {
                event.dataTransfer.effectAllowed = 'move';
                event.dataTransfer.setData('text/plain', JSON.stringify({
                    stage: node.dataset.nodeStage,
                    componentStage: node.dataset.nodeComponentStage || node.dataset.nodeStage,
                    id: node.dataset.nodeId
                }));
            });
            node.addEventListener('dragover', event => event.preventDefault());
            node.addEventListener('drop', async event => {
                event.preventDefault();
                const source = safeJson(event.dataTransfer.getData('text/plain'), null);
                const targetStage = node.dataset.nodeStage;
                if (!source || source.stage !== targetStage
                    || (source.componentStage === (node.dataset.nodeComponentStage || node.dataset.nodeStage)
                        && String(source.id) === String(node.dataset.nodeId))) return;
                if (targetStage === 'processing') await reorderProcessingSteps(source.componentStage, source.id, node.dataset.nodeComponentStage, node.dataset.nodeId);
                else await reorderStage(source.componentStage || source.stage, source.id, node.dataset.nodeId);
            });
        });
    }

    async function reorderStage(stage, sourceId, targetId) {
        const ordered = stageItems(stage);
        const from = ordered.findIndex(item => String(item.id) === String(sourceId));
        const to = ordered.findIndex(item => String(item.id) === String(targetId));
        if (from < 0 || to < 0) return;
        const [moved] = ordered.splice(from, 1);
        ordered.splice(to, 0, moved);
        const updates = ordered.map((item, index) => ({ item, priority: (index + 1) * 10 }));
        try {
            if (!state.demo) {
                for (const update of updates) {
                    if (Number(update.item.priority) === update.priority) continue;
                    await apiForStage(stage).update(update.item.id, serializeEntity(normalizeEntity({ ...update.item, priority: update.priority })));
                }
            }
            updates.forEach(update => { update.item.priority = update.priority; });
            showToast(`${stageLabel(stage)} order updated`, 'success');
            if (state.selected?.stage === stage) selectComponent(stage, state.selected.id, true);
            else renderAll();
        } catch (error) {
            showToast(error.message || 'Reorder failed', 'error');
            if (!state.demo) await loadStudioData({ messageType: state.messageType, selection: state.selected });
        }
    }

    async function reorderProcessingSteps(sourceStage, sourceId, targetStage, targetId) {
        const ordered = stageItems('processing');
        const from = ordered.findIndex(item => item.componentStage === sourceStage && String(item.id) === String(sourceId));
        const to = ordered.findIndex(item => item.componentStage === targetStage && String(item.id) === String(targetId));
        if (from < 0 || to < 0) return;
        const [moved] = ordered.splice(from, 1);
        ordered.splice(to, 0, moved);
        try {
            if (!state.demo) {
                await pipelineAPI.reorderProcessingSteps(state.messageType, ordered.map(item => ({
                    kind: item.componentStage,
                    id: Number(item.id)
                })));
            }
            ordered.forEach((item, index) => {
                const priority = (index + 1) * 10;
                item.priority = priority;
                const sourceItem = state.data[item.componentStage]?.find(candidate => String(candidate.id) === String(item.id));
                if (sourceItem) sourceItem.priority = priority;
            });
            showToast('Processing step order updated', 'success');
            if (state.selected?.stage === 'parser' || state.selected?.stage === 'transform') selectComponent(state.selected.stage, state.selected.id, true);
            else renderAll();
        } catch (error) {
            showToast(error.message || 'Reorder failed', 'error');
            if (!state.demo) await loadStudioData({ messageType: state.messageType, selection: state.selected });
        }
    }

    function createMessageType() {
        if (state.dirty && !window.confirm('Discard unsaved changes and create another message type?')) return;
        const value = window.prompt('New message type (case-sensitive)');
        if (!value || !value.trim()) return;
        const messageType = value.trim();
        if (!state.virtualMessageTypes.includes(messageType)) state.virtualMessageTypes.push(messageType);
        collectMessageTypes();
        changeMessageType(messageType, true);
    }

    async function changeMessageType(messageType, force = false) {
        if (!force && state.dirty && !window.confirm('Discard unsaved changes and switch pipelines?')) {
            renderHeader();
            return;
        }
        state.messageType = messageType;
        state.dirty = false;
        state.testRunId++;
        state.testResults.clear();
        await loadMapping();
        chooseInitialSelection();
    }

    function validatePipeline(showMessage = false) {
        syncDraftFromForm();
        if (state.mode === 'mapping') syncMappingFromForm();
        const issues = [];
        const inputs = stageItems('input').filter(item => item.enabled !== false);
        const outputs = stageItems('output').filter(item => item.enabled !== false);
        if (!inputs.length) issues.push('Add or enable at least one Input.');
        if (!outputs.length) issues.push('Add or enable at least one Output.');
        if (state.draft && state.selected?.stage !== 'structured') issues.push(...validateDraft(state.selected.stage, state.draft).map(error => error.message));
        issues.push(...validateMapping(state.mapping));
        state.valid = issues.length === 0;
        renderHeader();
        if (showMessage) showToast(issues[0] || 'Draft pipeline is internally consistent. Nothing was deployed.', issues.length ? 'error' : 'success');
        return issues;
    }

    async function deployPipeline() {
        if (state.dirty) {
            showToast('Save the current draft before deployment.', 'error');
            return;
        }
        if (validatePipeline().length) {
            showToast('Resolve validation errors before deployment.', 'error');
            return;
        }
        const button = document.getElementById('studio-deploy');
        button.disabled = true;
        button.textContent = 'Deploying…';
        try {
            if (!state.demo) await pipelineAPI.validateAndReload();
            showToast('Pipeline validated and reload started.', 'success');
        } catch (error) {
            showToast(error.message || 'Deployment failed', 'error');
        } finally {
            button.disabled = false;
            button.textContent = 'Deploy';
        }
    }

    async function runTest() {
        if (state.testRunning) return;
        syncDraftFromForm();
        if (state.mode === 'mapping') syncMappingFromForm();
        const { node, source } = testContext();
        if (!node || source.error) {
            renderTest();
            return;
        }
        const runId = ++state.testRunId;
        const signature = testSignature(node);
        state.testResults.delete(node.key);
        state.testRunning = true;
        renderTest();
        const startedAt = performance.now();
        let result;
        try {
            result = await simulateNode(node, source);
            result.stats = `${result.count ?? 1} event${result.count === 1 ? '' : 's'} · ${Math.max(1, Math.round(performance.now() - startedAt))} ms`;
        } catch (error) {
            result = { payload: { error: error.message || String(error) }, status: 'Draft test failed', stats: 'Failed', error: true, count: 0 };
        } finally {
            const current = testContext().nodes.find(item => item.key === node.key);
            if (runId === state.testRunId && current && testSignature(current) === signature) {
                state.testResults.set(node.key, { ...result, signature, revision: ++state.testRevision });
            }
            state.testRunning = false;
            renderTest();
        }
    }

    async function simulateNode(node, source) {
        const { stage, config } = node;
        const rawText = source.text;
        if (!rawText.trim()) throw new Error('Enter sample data before running a test.');
        const parsedInput = source.node ? source.payload : safeJson(rawText, null);
        const fields = parsedInput && typeof parsedInput === 'object' ? deepClone(parsedInput) : { raw: rawText };
        if (stage === 'input') {
            return { payload: inputPreview(parsedInput ?? { raw: rawText }, config), status: `${node.label} result previewed locally · no listener or external connection was opened`, count: 1 };
        }
        if (stage === 'parser') {
            if (['GrokParser', 'RegexParser'].includes(config.type) && !config.param?.trim()) throw new Error('Enter a parser pattern before running a test.');
            const sourceText = resolveParserInput(config, rawText, fields);
            if (sourceText == null) throw new Error(`Input field "${config.sourceField}" is missing from the test source.`);
            const parsed = state.demo ? localParse(config, sourceText, {})
                : await parserAPI.test({ type: config.type, param: config.param || null, sampleData: sourceText });
            const payload = mergeParserPayload(fields, config, parsed || {});
            return { payload, status: `${node.label} produced ${Object.keys(payload).length} fields`, count: 1 };
        }
        if (stage === 'transform') {
            const outcome = localTransform(config, fields);
            return { payload: outcome.fields, status: outcome.status, count: outcome.dropped ? 0 : 1 };
        }
        if (stage === 'structured') {
            const payload = state.demo ? localStructured(fields, config)
                : await structureAPI.simulate({ messageType: state.messageType, sampleData: fields, temporaryConfig: config });
            return { payload, status: 'Structured result split into common, subFields, and additionalAttributes', count: 1 };
        }
        if (stage === 'output') {
            return {
                payload: { destination: destinationSummary(config), serializedPayload: fields },
                status: 'Serialization previewed locally · no external delivery was attempted',
                count: 1
            };
        }
        throw new Error('Choose a supported component to test.');
    }

    function resolveParserInput(parser, rawText, fields) {
        if (!parser.sourceField || !String(parser.sourceField).trim()) return rawText;
        const value = fields?.[String(parser.sourceField).trim()];
        if (value == null) return null;
        if (parser.type === 'RegexParser' && Array.isArray(value)) return value;
        if (typeof value === 'string') return value;
        return JSON.stringify(value);
    }

    function mergeParserPayload(fields, parser, parsed) {
        const payload = { ...fields };
        const sourceField = String(parser.sourceField || '').trim();
        if (sourceField) {
            delete payload[sourceField];
            payload[sourceField] = parsed;
            return payload;
        }
        return { ...payload, ...parsed };
    }

    function localParse(parser, rawText, current) {
        if (rawText == null) return current;
        if (parser.type === 'JsonParser') return safeJson(rawText, current);
        if (parser.type === 'HttpParser') {
            const parts = rawText.split(/\r?\n\r?\n/);
            const lines = parts[0].split(/\r?\n/).slice(1);
            return { ...current, headers: Object.fromEntries(lines.map(line => line.split(/:\s*/, 2)).filter(parts => parts.length === 2).map(([key, value]) => [key.toUpperCase(), value])), body: parts.slice(1).join('\n\n') };
        }
        if (parser.type === 'RegexParser' && parser.param) {
            const result = {};
            const regex = new RegExp(parser.param, 'g');
            for (const value of Array.isArray(rawText) ? rawText : [rawText]) {
                if (value == null) continue;
                const text = typeof value === 'string' ? value : JSON.stringify(value);
                for (const match of text.matchAll(regex)) {
                    if (match.groups) {
                        for (const [name, captured] of Object.entries(match.groups)) {
                            if (captured != null) {
                                result[name] = captured;
                                if (name.toLowerCase() === 'attributes') addAttributeFields(result, captured);
                            }
                        }
                    } else if (match[1] != null && match[2] != null) {
                        result[match[1]] = match[2];
                    }
                }
            }
            return { ...current, ...result };
        }
        return current;
    }

    function addAttributeFields(fields, attributes) {
        const matcher = /([A-Za-z_][A-Za-z0-9_.-]*)\s*=\s*(?:"((?:\\.|[^"\\])*)"|(\S+))/g;
        for (const match of attributes.matchAll(matcher)) {
            fields[match[1]] = (match[2] ?? match[3]).replace(/\\(["\\])/g, '$1');
        }
    }

    function localTransform(transform, sourceFields) {
        const fields = deepClone(sourceFields);
        if (transform.type === 'Filter') {
            const drop = safeJson(transform.filterDrop, {});
            const pass = safeJson(transform.filterPass, {});
            const dropMatch = Object.entries(drop).find(([key, values]) => splitValues(values).includes(String(fields[key])));
            if (dropMatch) return { fields: { dropped: true, matched: { field: dropMatch[0], value: fields[dropMatch[0]] } }, dropped: true, matched: dropMatch[0], status: `Dropped · matched ${dropMatch[0]}=${fields[dropMatch[0]]}` };
            const failedPass = Object.entries(pass).find(([key, values]) => !splitValues(values).includes(String(fields[key])));
            if (failedPass) return { fields: { dropped: true, missingPass: failedPass[0] }, dropped: true, matched: failedPass[0], status: `Dropped · pass condition failed for ${failedPass[0]}` };
            return { fields, dropped: false, status: 'Passed · all active filter conditions succeeded' };
        }
        if (transform.type === 'AddProperty') {
            const groups = safeJson(transform.addProperties, {});
            const moved = [];
            Object.entries(groups).forEach(([target, sources]) => {
                fields[target] = {};
                (Array.isArray(sources) ? sources : splitValues(sources)).forEach(source => {
                    fields[target][source] = Object.prototype.hasOwnProperty.call(fields, source) ? fields[source] : null;
                    delete fields[source];
                    moved.push(source);
                });
            });
            return { fields, dropped: false, status: `Grouped ${moved.length} field${moved.length === 1 ? '' : 's'} into ${Object.keys(groups).join(', ') || 'nested objects'}` };
        }
        if (transform.type === 'RemoveProperty') {
            const remove = parseJsonList(transform.removeProperties);
            const removed = remove.filter(key => Object.prototype.hasOwnProperty.call(fields, key));
            remove.forEach(key => delete fields[key]);
            return { fields, dropped: false, status: `Removed ${removed.length} field${removed.length === 1 ? '' : 's'}${removed.length ? ` · ${removed.join(', ')}` : ''}` };
        }
        return { fields, dropped: false, status: 'Transform preview complete' };
    }

    function localStructured(fields, mapping) {
        const common = { ingestTime: new Date().toISOString(), logSource: state.messageType, rawLog: state.sampleInput };
        const used = new Set();
        (mapping?.commonMappings || []).forEach(row => {
            if (!row.targetField) return;
            const value = fields[row.sourceField] ?? row.defaultValue;
            if (value != null) common[toCamelCase(row.targetField)] = value;
            if (row.sourceField) used.add(row.sourceField);
        });
        const rule = (mapping?.subTableRules || [])[0];
        const subFields = {};
        (rule?.mappings || []).forEach(row => {
            subFields[row.targetField] = fields[row.sourceField] ?? row.defaultValue;
            used.add(row.sourceField);
        });
        return {
            common,
            subDomainType: rule?.targetSubTable || null,
            subFields,
            additionalAttributes: Object.fromEntries(Object.entries(fields).filter(([key]) => !used.has(key)))
        };
    }

    function destinationSummary(output) {
        if (!output) return { type: 'unknown' };
        const config = safeConfig(output);
        if (output.type === 'ClickHouseOutputAdapter') return { type: 'ClickHouse', endpoint: config.endpointUrl, database: config.database, table: config.tableName };
        if (output.type === 'MariaDbOutputAdapter') return { type: 'MariaDB', jdbcUrl: config.jdbcUrl, table: config.tableName };
        if (output.type === 'HttpOutputAdapter') return { type: 'HTTP', method: output.method || 'POST', url: output.url };
        if (output.type === 'KafkaOutputAdapter') return { type: 'Kafka', brokers: output.bootstrapservers, topic: output.topicid, key: output.key || null };
        if (output.type === 'TcpOutputAdapter') return { type: 'TCP', host: output.host, port: output.port };
        if (output.type === 'OpenSearchOutputAdapter') return { type: 'OpenSearch', baseUrl: output.url, indexTemplate: output.indexTemplate, action: '_doc' };
        if (output.type === 'RabbitMQAdapter') return { type: 'RabbitMQ', host: output.host, exchange: output.exchange, routingKey: output.routingkey };
        return { type: getTypeDef('output', output.type)?.label || output.type };
    }

    function autoMap() {
        const sample = safeJson(state.sampleInput, {});
        const aliases = { timestamp: 'event_time', level: 'severity', src_ip: 'src_ip', src_port: 'src_port', dst_ip: 'dst_ip', dst_port: 'dst_port', host: 'src_host', user: 'user_name', protocol: 'protocol' };
        const existingTargets = new Set(state.mapping.commonMappings.map(row => row.targetField));
        Object.keys(sample).forEach(source => {
            const target = aliases[source];
            if (target && !existingTargets.has(target)) state.mapping.commonMappings.push({ sourceField: source, targetField: target, defaultValue: null });
        });
        markDirty();
        renderSettings();
    }

    function allTypeDefs(stage) {
        const local = TYPE_DEFS[stage] || [];
        const seen = new Set(local.map(item => item.type));
        const extra = (state.metadata[stage] || []).map(item => {
            const type = item.className || item.type;
            return genericTypeDef(stage, type, item.displayName || item.name, item.description);
        }).filter(item => item.type && !seen.has(item.type));
        return [...local, ...extra];
    }

    function getTypeDef(stage, type) {
        return (TYPE_DEFS[stage] || []).find(item => item.type === type) || allTypeDefs(stage).find(item => item.type === type) || null;
    }

    function genericTypeDef(stage, type, label, description) {
        return def(type, label || humanizeType(type), STAGES.find(item => item.key === stage)?.icon || 'extension', description || 'Configure this component with its persisted REST fields.', ['general'], []);
    }

    function fieldVisibleOnTab(item, tab) {
        if (!item.tab) return true;
        return Array.isArray(item.tab) ? item.tab.includes(tab) : item.tab === tab;
    }

    function stageLabel(stage) {
        return STAGES.find(item => item.key === stage)?.label || stage;
    }

    function tabLabel(tab) {
        const labels = { tls: 'TLS', mtls: 'mTLS', dlq: 'DLQ', advanced: 'Advanced', general: 'General', rules: 'Rules', mapping: 'Mapping' };
        return labels[tab] || tab.charAt(0).toUpperCase() + tab.slice(1);
    }

    function apiForStage(stage) {
        return { input: inputAdapterAPI, parser: parserAPI, transform: transformAPI, output: outputAdapterAPI }[stage];
    }

    function safeConfig(item) {
        return typeof item?.configParams === 'string' ? safeJson(item.configParams, {}) : (item?.configParams || {});
    }

    function availableSourceFields() {
        const fields = new Set();
        const { source } = testContext();
        const sample = source.node ? source.payload : safeJson(state.sampleInput, null);
        if (sample && typeof sample === 'object' && !Array.isArray(sample)) {
            Object.keys(sample).forEach(key => fields.add(key));
        }
        stageItems('parser').forEach(item => {
            if (item.sourceField) fields.add(item.sourceField);
        });
        return [...fields].sort((a, b) => a.localeCompare(b));
    }

    function configValue(item, key) {
        return safeConfig(item)[key];
    }

    function mapCount(value) {
        return Object.keys(safeJson(value, {})).length;
    }

    function listCount(value) {
        return parseJsonList(value).length;
    }

    function parseJsonList(value) {
        if (Array.isArray(value)) return value;
        const parsed = safeJson(value, null);
        if (Array.isArray(parsed)) return parsed;
        if (typeof value === 'string') return value.split(',').map(item => item.trim()).filter(Boolean);
        return [];
    }

    function safeJson(value, fallback) {
        if (value == null || value === '') return fallback;
        if (typeof value === 'object') return value;
        try { return JSON.parse(value); } catch (_) { return fallback; }
    }

    function splitValues(value) {
        return String(value ?? '').split(',').map(item => item.trim()).filter(Boolean);
    }

    function getPath(object, path) {
        return String(path).split('.').reduce((value, key) => value == null ? undefined : value[key], object);
    }

    function setPath(object, path, value) {
        const parts = String(path).split('.');
        let cursor = object;
        parts.slice(0, -1).forEach(key => {
            if (!cursor[key] || typeof cursor[key] !== 'object') cursor[key] = {};
            cursor = cursor[key];
        });
        cursor[parts[parts.length - 1]] = value;
    }

    function isBlank(value) {
        return value == null || value === '' || (Array.isArray(value) && value.length === 0) || (typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0);
    }

    function humanizeType(type) {
        return String(type || 'Component').replace(/Adapter$/, '').replace(/Parser$/, ' parser').replace(/([a-z0-9])([A-Z])/g, '$1 $2');
    }

    function humanBytes(bytes) {
        if (!Number.isFinite(bytes) || bytes <= 0) return 'bytes';
        if (bytes >= 1073741824) return `${trimNumber(bytes / 1073741824)} GiB`;
        if (bytes >= 1048576) return `${trimNumber(bytes / 1048576)} MiB`;
        if (bytes >= 1024) return `${trimNumber(bytes / 1024)} KiB`;
        return `${bytes} B`;
    }

    function trimNumber(value) {
        return Number.isInteger(value) ? String(value) : value.toFixed(1);
    }

    function toCamelCase(value) {
        return String(value).replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
    }

    function deepClone(value) {
        return value == null ? value : JSON.parse(JSON.stringify(value));
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
    }

    function escapeAttr(value) {
        return escapeHtml(value).replace(/`/g, '&#96;');
    }

    function cssEscape(value) {
        return window.CSS?.escape ? window.CSS.escape(value) : String(value).replace(/["\\]/g, '\\$&');
    }

    function syntaxHighlight(value) {
        const json = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
        const escaped = escapeHtml(json ?? '');
        return escaped.replace(/(&quot;(?:\\u[a-fA-F0-9]{4}|\\[^u]|[^\\&])*&quot;)(\s*:)?|\b(true|false)\b|\b(null)\b|-?\d+(?:\.\d+)?(?:[eE][+\-]?\d+)?/g, match => {
            if (/^&quot;/.test(match)) return `<span class="${/:$/.test(match) ? 'studio-json-key' : 'studio-json-string'}">${match}</span>`;
            if (/true|false/.test(match)) return `<span class="studio-json-boolean">${match}</span>`;
            if (/null/.test(match)) return `<span class="studio-json-null">${match}</span>`;
            return `<span class="studio-json-number">${match}</span>`;
        });
    }

    function showToast(message, type = 'info') {
        document.querySelector('.studio-toast')?.remove();
        const toast = document.createElement('div');
        toast.className = `studio-toast is-${type}`;
        toast.setAttribute('role', 'status');
        toast.textContent = message;
        document.body.appendChild(toast);
        setTimeout(() => toast.remove(), 3200);
    }

    function demoData() {
        return {
            input: [{
                id: 1,
                type: 'TcpMtlsGzipInputAdapter', messagetype: 'castrelyx-agent-item', enabled: true,
                port: 6514, timeoutMs: 30000, queueSize: 10000, workerThreads: 32,
                configParams: JSON.stringify({
                    keyStorePath: '/etc/logparser/keystores/agent-input.p12',
                    keyStorePasswordEnv: 'LP_INPUT_KEYSTORE_PASSWORD',
                    trustStorePath: '/etc/logparser/keystores/agent-trust.p12',
                    trustStorePasswordEnv: 'LP_INPUT_TRUSTSTORE_PASSWORD',
                    maxFrameBytes: 10485760, maxConnections: 32, tlsReloadIntervalMs: 5000, ackMode: 'queueAccepted'
                })
            }],
            parser: [{ id: 11, type: 'JsonParser', messagetype: 'castrelyx-agent-item', priority: 10, continueOnFailure: false, enabled: true }],
            transform: [
                { id: 21, type: 'AddProperty', messagetype: 'castrelyx-agent-item', priority: 20, addProperties: '{"network":["src_ip","dst_port"]}', enabled: true },
                { id: 22, type: 'RemoveProperty', messagetype: 'castrelyx-agent-item', priority: 30, removeProperties: '["debug"]', enabled: true }
            ],
            output: [
                { id: 31, type: 'ClickHouseOutputAdapter', messagetype: 'castrelyx-agent-item', timeoutMs: 30000, enabled: true, configParams: '{"endpointUrl":"http://clickhouse:9000","database":"logs","tableName":"events","metricTableName":"manager_metric_samples","stateTableName":"manager_state_snapshots","eventTableName":"manager_events","batchSize":100,"flushIntervalMs":5000,"maxPendingBytes":67108864,"writeTelemetryTables":true}' },
                { id: 32, type: 'ConsoleOutputAdapter', messagetype: 'castrelyx-agent-item', enabled: true, addOriginText: false }
            ]
        };
    }

    function demoMapping(messageType) {
        const pairs = [
            ['timestamp', 'event_time'], ['level', 'severity'], ['src_ip', 'src_ip'], ['dst_port', 'dst_port'],
            ['host', 'src_host'], ['message', 'event_action'], ['agent_id', 'user_id'], ['protocol', 'protocol'],
            ['event_category', 'event_category'], ['event_type', 'event_type'], ['event_result', 'event_result'],
            ['dst_ip', 'dst_ip'], ['src_port', 'src_port'], ['source', 'log_source']
        ];
        return {
            id: `${messageType}-v1`, messageType,
            commonMappings: pairs.map(([sourceField, targetField]) => ({ sourceField, targetField, defaultValue: null })),
            subTableRules: [{ targetSubTable: 'event_network', conditionExpression: "dst_port != null", mappings: [{ sourceField: 'dst_port', targetField: 'destination_port', defaultValue: null }] }]
        };
    }

    return { mount, reload: loadStudioData };
})();

window.PipelineStudio = PipelineStudio;
