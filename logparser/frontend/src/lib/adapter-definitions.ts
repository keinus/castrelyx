import type {
  Stage,
  AdapterDefinition,
  FieldDefinition,
  FieldKind,
} from "./types";

// One schema powers both the inventory editor and Pipeline Studio.
export const TYPE_DEFS: Record<Stage, AdapterDefinition[]> = {
  input: [
    def(
      "FileInputAdapter",
      "File",
      "description",
      "Tail a UTF-8 log file",
      ["source", "advanced"],
      [
        field("path", "File path", "text", {
          required: true,
          tab: "source",
          help: "Absolute or service-relative path to the log file.",
        }),
        field("isFromBeginning", "Read from beginning", "boolean", {
          default: false,
          tab: "source",
          help: "Only applies when the file is opened for the first time.",
        }),
        field("host", "Source host fallback", "text", {
          default: "localhost",
          tab: "advanced",
          help: "Used as source metadata when the line has no host.",
        }),
      ],
      "Reads new UTF-8 lines from a regular file.",
    ),
    def(
      "TcpInputAdapter",
      "TCP",
      "settings_ethernet",
      "Newline-delimited TCP listener",
      ["connection", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: "connection",
        }),
      ],
      "All interfaces · newline-delimited UTF-8 · one line per event.",
    ),
    def(
      "TlsTcpInputAdapter",
      "TLS TCP",
      "enhanced_encryption",
      "TLS protected TCP listener",
      ["connection", "tls", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: ["connection", "tls"],
        }),
        ...serverTlsFields(),
      ],
      "TLS server listener · optional client certificate authentication.",
    ),
    def(
      "UdpInputAdapter",
      "UDP",
      "radar",
      "One datagram per event",
      ["connection", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: "connection",
        }),
      ],
      "All interfaces · 1 datagram = 1 event · maximum 1,600 bytes.",
    ),
    def(
      "HttpInputAdapter",
      "HTTP",
      "http",
      "Capture complete HTTP requests",
      ["connection", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: "connection",
        }),
      ],
      "Receives every path and creates one event from the request line, headers, and body.",
      "This adapter is a raw HTTP collector and does not guarantee webhook-style responses.",
    ),
    def(
      "HttpsInputAdapter",
      "HTTPS",
      "https",
      "TLS HTTP request collector",
      ["connection", "tls", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: ["connection", "tls"],
        }),
        ...serverTlsFields(),
      ],
      "Receives all HTTPS paths · full request becomes one event.",
      "This adapter is a raw request collector; codec and path routing are not runtime settings.",
    ),
    def(
      "KafkaInputAdapter",
      "Kafka",
      "hub",
      "Consume a Kafka topic",
      ["connection", "subscription", "advanced"],
      [
        field("bootstrapservers", "Bootstrap servers", "text", {
          required: true,
          tab: "connection",
          help: "Comma-separated broker addresses.",
        }),
        field("topicid", "Topic", "text", {
          required: true,
          tab: "subscription",
        }),
        field("groupId", "Consumer group", "text", {
          tab: "subscription",
          help: "When empty, a UUID is generated at each startup.",
        }),
      ],
      "Consumes records from a Kafka topic as pipeline events.",
    ),
    def(
      "SnmpInputAdapter",
      "SNMP poller",
      "sensors",
      "Poll one or more SNMP targets",
      ["polling", "targets", "advanced"],
      [
        field("configParams.version", "Default version", "select", {
          default: "2c",
          choices: ["1", "2c", "3"],
          tab: "polling",
        }),
        field("configParams.community", "Default community", "password", {
          default: "public",
          tab: "polling",
        }),
        field("configParams.intervalMs", "Poll interval", "number", {
          default: 60000,
          min: 1000,
          unit: "ms",
          tab: "polling",
        }),
        field("timeoutMs", "Timeout", "number", {
          default: 5000,
          min: 100,
          unit: "ms",
          tab: "polling",
        }),
        field("configParams.retries", "Retries", "number", {
          default: 0,
          min: 0,
          tab: "polling",
        }),
        field("workerThreads", "Worker threads", "number", {
          default: 1,
          min: 1,
          tab: "advanced",
        }),
        field("queueSize", "Queue size", "number", {
          default: 1000,
          min: 1,
          tab: "advanced",
        }),
        field("configParams.targets", "Targets", "json", {
          required: true,
          wide: true,
          tab: "targets",
          default: [
            {
              name: "router-01",
              host: "192.0.2.10",
              port: 161,
              version: "2c",
              community: "public",
            },
          ],
          help: "Each target supports v1/v2c community or v3 security fields.",
        }),
        field("configParams.oids", "OIDs", "json", {
          required: true,
          wide: true,
          tab: "targets",
          default: [{ name: "sysUpTime", oid: "1.3.6.1.2.1.1.3.0" }],
          help: "Provide at least one {name, oid} entry. A plain OID string is also accepted.",
        }),
      ],
      "Poll interval, target credentials, and OIDs are stored inside configParams.",
    ),
    def(
      "RabbitMqInputAdapter",
      "RabbitMQ",
      "move_to_inbox",
      "Consume a RabbitMQ queue",
      ["connection", "subscription", "advanced"],
      rabbitFields(false),
      "Consumes queue messages using explicit acknowledgement by default.",
      "The password is stored in configParams; protect access to the configuration database and its backups.",
    ),
    def(
      "TlsRabbitMqInputAdapter",
      "TLS RabbitMQ",
      "lock",
      "Consume a RabbitMQ queue over TLS",
      ["connection", "subscription", "tls", "advanced"],
      rabbitFields(true),
      "RabbitMQ client TLS is always enabled; the default port is 5671.",
    ),
    def(
      "TcpMtlsGzipInputAdapter",
      "TCP mTLS + gzip",
      "cell_tower",
      "Castrelyx agent framed batches",
      ["connection", "mtls", "capacity", "advanced"],
      [
        field("port", "Listen port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: ["connection", "capacity"],
        }),
        field("timeoutMs", "Idle timeout", "number", {
          required: true,
          default: 30000,
          min: 1,
          unit: "ms",
          tab: ["connection", "capacity"],
        }),
        field("queueSize", "Queue size", "number", {
          required: true,
          default: 10000,
          min: 1,
          tab: ["connection", "capacity"],
        }),
        field("configParams.ackMode", "Acknowledge mode", "text", {
          default: "queueAccepted",
          readonly: true,
          tab: ["connection", "advanced"],
          help: "Events are acknowledged after the in-memory queue accepts the complete batch.",
        }),
        field("configParams.keyStorePath", "Key store path", "text", {
          required: true,
          tab: ["connection", "mtls"],
          help: "Path to the PKCS12 server key store.",
        }),
        field(
          "configParams.keyStorePasswordEnv",
          "Key store password env",
          "text",
          {
            required: true,
            tab: ["connection", "mtls"],
            help: "Environment variable containing the key store password.",
          },
        ),
        field("configParams.trustStorePath", "Trust store path", "text", {
          required: true,
          tab: ["connection", "mtls"],
          help: "Path to the PKCS12 client trust store.",
        }),
        field(
          "configParams.trustStorePasswordEnv",
          "Trust store password env",
          "text",
          {
            required: true,
            tab: ["connection", "mtls"],
            help: "Environment variable containing the trust store password.",
          },
        ),
        field("workerThreads", "Worker threads", "number", {
          required: true,
          default: 32,
          min: 1,
          tab: ["connection", "capacity"],
          help: "Fallback for maximum connections.",
        }),
        field("configParams.maxFrameBytes", "Maximum frame size", "bytes", {
          required: true,
          default: 10485760,
          min: 1,
          tab: ["connection", "capacity"],
        }),
        field("configParams.maxConnections", "Maximum connections", "number", {
          required: true,
          default: 32,
          min: 1,
          tab: ["connection", "capacity"],
        }),
        field(
          "configParams.tlsReloadIntervalMs",
          "TLS reload interval",
          "number",
          {
            required: true,
            default: 5000,
            min: 1,
            unit: "ms",
            tab: ["connection", "advanced"],
          },
        ),
      ],
      "TLSv1.3 / TLSv1.2 · client auth required · PKCS12 · gzip JSON batches.",
    ),
    def(
      "FakeInputAdapter",
      "Fake events",
      "science",
      "Generate a sample alert event",
      ["general"],
      [],
      "Creates one Suricata-like alert event per invocation; there is no interval argument.",
    ),
  ],
  parser: [
    parserDef(
      "JsonParser",
      "JSON parser",
      "data_object",
      "Merge a JSON object into the event field map.",
    ),
    parserDef(
      "GrokParser",
      "Grok parser",
      "code",
      "Extract named captures with a Grok pattern.",
      true,
    ),
    parserDef(
      "RegexParser",
      "Regex parser",
      "regular_expression",
      "Extract named captures or use capture groups 1 and 2 as key/value.",
      true,
    ),
    parserDef(
      "RFC3164SyslogParser",
      "RFC3164 syslog",
      "terminal",
      "Parse classic BSD syslog fields.",
    ),
    parserDef(
      "RFC5424SyslogParser",
      "RFC5424 syslog",
      "terminal",
      "Parse versioned syslog and structured data.",
    ),
    parserDef(
      "HttpParser",
      "HTTP parser",
      "http",
      "Create a headers map and body field from a raw request.",
    ),
  ],
  transform: [
    def(
      "Filter",
      "Filter events",
      "filter_alt",
      "Drop or pass events by exact field value",
      ["rules", "advanced"],
      [
        field("priority", "Order", "number", {
          default: 10,
          min: 0,
          tab: "advanced",
        }),
        field("filterDrop", "Drop when any condition matches", "keyValue", {
          wide: true,
          tab: "rules",
          valueLabel: "Comma-separated blocked values",
          help: "Drop rules run first and use exact, case-sensitive matching.",
        }),
        field("filterPass", "Pass only when all conditions match", "keyValue", {
          wide: true,
          tab: "rules",
          valueLabel: "Comma-separated allowed values",
          help: "Every pass field must exist and match one allowed value.",
        }),
      ],
      "Drop rules are evaluated before pass rules.",
    ),
    def(
      "AddProperty",
      "Group fields",
      "device_hub",
      "Move flat fields into nested objects",
      ["mapping", "advanced"],
      [
        field("priority", "Order", "number", {
          default: 20,
          min: 0,
          tab: "advanced",
        }),
        field("addProperties", "Target objects and source fields", "mapList", {
          wide: true,
          required: true,
          tab: "mapping",
          help: "Source fields are removed from the top level and moved below the target object.",
        }),
      ],
      "Canonical type: AddProperty · existing target objects are overwritten.",
    ),
    def(
      "RemoveProperty",
      "Remove fields",
      "delete_outline",
      "Remove top-level fields",
      ["fields", "advanced"],
      [
        field("priority", "Order", "number", {
          default: 30,
          min: 0,
          tab: "advanced",
        }),
        field("removeProperties", "Fields to remove", "jsonList", {
          wide: true,
          required: true,
          tab: "fields",
          help: "Comma-separated exact top-level field names. Nested paths are not supported.",
        }),
      ],
      "Removes exact top-level keys after parsing and earlier transforms.",
    ),
  ],
  output: [
    def(
      "ConsoleOutputAdapter",
      "Console",
      "terminal",
      "Write the final JSON to the application log",
      ["serialization"],
      [
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "serialization",
        }),
      ],
      "Shows the final console JSON serialization without external delivery.",
    ),
    def(
      "BenchmarkAdapter",
      "Benchmark",
      "speed",
      "Record processing throughput",
      ["general"],
      [],
      "No external delivery or JSON serialization; throughput is logged once per interval.",
    ),
    def(
      "TcpOutputAdapter",
      "TCP",
      "settings_ethernet",
      "Send one JSON event per TCP connection",
      ["destination", "reliability", "advanced"],
      [
        field("host", "Destination host", "text", {
          required: true,
          tab: "destination",
        }),
        field("port", "Destination port", "number", {
          required: true,
          min: 1,
          max: 65535,
          tab: "destination",
        }),
        field("timeoutMs", "Timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "reliability",
        }),
        field("retryCount", "Retry count", "number", {
          default: 3,
          min: 0,
          tab: "reliability",
        }),
        field("retryDelayMs", "Retry delay", "number", {
          default: 1000,
          min: 1,
          unit: "ms",
          tab: "reliability",
        }),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Opens a connection per event · UTF-8 JSON · no delimiter or length prefix.",
    ),
    def(
      "HttpOutputAdapter",
      "HTTP",
      "http",
      "Deliver JSON to an HTTP endpoint",
      ["destination", "headers", "advanced"],
      [
        field("url", "Endpoint URL", "url", {
          required: true,
          tab: "destination",
        }),
        field("method", "Method", "select", {
          default: "POST",
          choices: ["POST", "PUT", "PATCH"],
          tab: "destination",
        }),
        field("timeoutMs", "Timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "destination",
        }),
        field("headers", "Headers", "keyValue", {
          wide: true,
          tab: "headers",
          valueLabel: "Value",
          help: "Authorization values are kept in the saved payload and masked when the backend masks them.",
        }),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Automatically adds Content-Type: application/json and User-Agent: LogParser/1.0; only 2xx is success.",
    ),
    def(
      "KafkaOutputAdapter",
      "Kafka",
      "hub",
      "Produce final events to Kafka",
      ["destination", "reliability", "advanced"],
      [
        field("bootstrapservers", "Bootstrap servers", "text", {
          required: true,
          tab: "destination",
        }),
        field("topicid", "Topic", "text", {
          required: true,
          tab: "destination",
        }),
        field("key", "Record key", "text", { tab: "destination" }),
        field("timeoutMs", "Timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "reliability",
        }),
        field("retryCount", "Retry count", "number", {
          default: 0,
          min: 0,
          tab: "reliability",
        }),
        field("retryDelayMs", "Retry delay", "number", {
          default: 250,
          min: 1,
          unit: "ms",
          tab: "reliability",
        }),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "acks=all · lz4 compression · idempotence disabled.",
    ),
    def(
      "OpenSearchOutputAdapter",
      "OpenSearch",
      "manage_search",
      "Index events into OpenSearch",
      ["destination", "authentication", "advanced"],
      [
        field("url", "Base URL", "url", { required: true, tab: "destination" }),
        field("indexTemplate", "Index template", "text", {
          required: true,
          tab: "destination",
          help: "Supports %{field} and Java date patterns such as %{yyMMdd}.",
        }),
        field("timeoutMs", "Timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "destination",
        }),
        field("osUsername", "Username", "text", { tab: "authentication" }),
        field("osPassword", "Password", "password", { tab: "authentication" }),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Always sends POST {base}/{index}/_doc.",
      "Current runtime trusts self-signed leaf certificates and disables hostname verification.",
    ),
    def(
      "RabbitMQAdapter",
      "RabbitMQ",
      "move_to_inbox",
      "Publish to a topic exchange",
      ["destination", "authentication", "advanced"],
      [
        field("host", "Host", "text", { required: true, tab: "destination" }),
        field("rmqPort", "Port", "number", {
          default: 5672,
          min: 1,
          max: 65535,
          tab: "destination",
        }),
        field("exchange", "Exchange", "text", {
          required: true,
          tab: "destination",
        }),
        field("routingkey", "Routing key", "text", {
          required: true,
          tab: "destination",
        }),
        field("timeoutMs", "Publisher confirm timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "destination",
        }),
        field("rmqUsername", "Username", "text", { tab: "authentication" }),
        field("rmqPassword", "Password", "password", { tab: "authentication" }),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Declares a TOPIC exchange before publishing.",
      "TLS output is not supported. Use a trusted network boundary or another output adapter.",
    ),
    def(
      "MariaDbOutputAdapter",
      "MariaDB",
      "storage",
      "Batch structured events into MariaDB",
      ["connection", "batching", "advanced"],
      [
        field("configParams.jdbcUrl", "JDBC URL", "text", {
          required: true,
          tab: "connection",
        }),
        field(
          "configParams.usernameEnv",
          "Username environment variable",
          "text",
          { required: true, tab: "connection" },
        ),
        field(
          "configParams.passwordEnv",
          "Password environment variable",
          "text",
          { required: true, tab: "connection" },
        ),
        field("configParams.tableName", "Table name", "text", {
          default: "castrelyx_agent_events",
          required: true,
          pattern: "^[A-Za-z0-9_]+$",
          tab: "connection",
        }),
        field("configParams.batchSize", "Batch size", "number", {
          default: 100,
          min: 1,
          tab: "batching",
        }),
        field("configParams.flushIntervalMs", "Flush interval", "number", {
          default: 5000,
          min: 1,
          unit: "ms",
          tab: "batching",
        }),
        field(
          "configParams.autoCreateSchema",
          "Auto-create schema",
          "boolean",
          { default: false, tab: "advanced" },
        ),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Credentials are resolved from environment variables; batching lives inside configParams.",
    ),
    def(
      "ClickHouseOutputAdapter",
      "ClickHouse",
      "view_column",
      "Write raw events and telemetry tables",
      ["connection", "tables", "buffering", "dlq", "advanced"],
      [
        field("configParams.endpointUrl", "Endpoint URL", "url", {
          required: true,
          tab: "connection",
        }),
        field("configParams.database", "Database", "text", {
          default: "default",
          pattern: "^[A-Za-z0-9_]+$",
          tab: "connection",
        }),
        field(
          "configParams.usernameEnv",
          "Username environment variable",
          "text",
          { tab: "connection" },
        ),
        field(
          "configParams.passwordEnv",
          "Password environment variable",
          "text",
          { tab: "connection" },
        ),
        field("timeoutMs", "Request timeout", "number", {
          default: 30000,
          min: 1,
          unit: "ms",
          tab: "connection",
        }),
        field("configParams.tableName", "Raw table", "text", {
          required: true,
          default: "castrelyx_agent_events",
          pattern: "^[A-Za-z0-9_]+$",
          tab: "tables",
        }),
        field("configParams.metricTableName", "Metric table", "text", {
          default: "manager_metric_samples",
          pattern: "^[A-Za-z0-9_]+$",
          tab: "tables",
        }),
        field("configParams.stateTableName", "State table", "text", {
          default: "manager_state_snapshots",
          pattern: "^[A-Za-z0-9_]+$",
          tab: "tables",
        }),
        field("configParams.eventTableName", "Event table", "text", {
          default: "manager_events",
          pattern: "^[A-Za-z0-9_]+$",
          tab: "tables",
        }),
        field(
          "configParams.writeTelemetryTables",
          "Write telemetry tables",
          "boolean",
          { default: true, tab: "tables" },
        ),
        field("configParams.batchSize", "Batch size", "number", {
          default: 100,
          min: 1,
          tab: "buffering",
          help: "Events per write.",
        }),
        field("configParams.flushIntervalMs", "Flush interval", "number", {
          default: 5000,
          min: 1,
          unit: "ms",
          tab: "buffering",
          help: "Maximum wait before flushing a partial batch.",
        }),
        field(
          "configParams.incompleteGroupTimeoutMs",
          "Incomplete group timeout",
          "number",
          {
            default: 30000,
            min: 1,
            unit: "ms",
            tab: "buffering",
            help: "Wait for the remaining telemetry chunks.",
          },
        ),
        field(
          "configParams.maxPendingGroups",
          "Maximum pending groups",
          "number",
          { default: 2048, min: 1, tab: "buffering" },
        ),
        field(
          "configParams.maxPendingItems",
          "Maximum pending items",
          "number",
          { default: 50000, min: 1, tab: "buffering" },
        ),
        field(
          "configParams.maxPendingBytes",
          "Maximum pending bytes",
          "bytes",
          { default: 67108864, min: 1, tab: "buffering" },
        ),
        field("configParams.incompleteChunkDlqDir", "DLQ directory", "text", {
          default: "${user.home}/logparser/data/incomplete-chunks",
          tab: "dlq",
        }),
        field(
          "configParams.maxIncompleteChunkDlqBytes",
          "Maximum DLQ bytes",
          "bytes",
          { default: 134217728, min: 1, tab: "dlq" },
        ),
        field(
          "configParams.maxIncompleteChunkDlqRecords",
          "Maximum DLQ records",
          "number",
          { default: 1000, min: 1, tab: "dlq" },
        ),
        field(
          "configParams.autoCreateSchema",
          "Auto-create schema",
          "boolean",
          { default: false, tab: "advanced" },
        ),
        field("addOriginText", "Include original text", "boolean", {
          default: false,
          tab: "advanced",
        }),
      ],
      "Structured writes, bounded buffering, and incomplete-chunk DLQ are configured independently.",
    ),
  ],
};

function def(
  type: string,
  label: string,
  icon: string,
  description: string,
  tabs: string[],
  fields: FieldDefinition[],
  notice?: string,
  warning?: string,
): AdapterDefinition {
  return { type, label, icon, description, tabs, fields, notice, warning };
}

function field(
  path: string,
  label: string,
  type: FieldKind,
  options: Partial<FieldDefinition> = {},
): FieldDefinition {
  return { path, label, type, ...options };
}

function parserDef(
  type: string,
  label: string,
  icon: string,
  description: string,
  needsPattern = false,
) {
  const fields = [
    field("priority", "Execution order", "number", {
      default: 10,
      min: 0,
      tab: "behavior",
    }),
    field("sourceField", "Source field", "text", {
      tab: "behavior",
      list: "studio-source-fields",
      placeholder: "Raw event (originalText)",
      help: "Blank parses original log text. A field path parses and replaces that field.",
    }),
    field("continueOnFailure", "Continue on failure", "boolean", {
      default: false,
      tab: "behavior",
      help: "When enabled, processing continues with the next step after this parser fails.",
    }),
  ];
  if (needsPattern) {
    fields.unshift(
      field(
        "param",
        type === "GrokParser" ? "Grok pattern" : "Java regular expression",
        "textarea",
        { required: true, wide: true, tab: "pattern" },
      ),
    );
  }
  return def(
    type,
    label,
    icon,
    description,
    needsPattern
      ? ["pattern", "behavior", "advanced"]
      : ["behavior", "advanced"],
    fields,
    description,
  );
}

function serverTlsFields() {
  return [
    field("configParams.keyStorePath", "Key store path", "text", {
      required: true,
      tab: "tls",
    }),
    field("configParams.keyStorePassword", "Key store password", "password", {
      tab: "tls",
      help: "Use this or the environment variable field.",
    }),
    field(
      "configParams.keyStorePasswordEnv",
      "Key store password env",
      "text",
      { tab: "tls" },
    ),
    field("configParams.keyStoreType", "Key store type", "select", {
      default: "PKCS12",
      choices: ["PKCS12", "JKS"],
      tab: "tls",
    }),
    field("configParams.keyPassword", "Private key password", "password", {
      tab: "tls",
    }),
    field("configParams.keyPasswordEnv", "Private key password env", "text", {
      tab: "tls",
    }),
    field("configParams.clientAuth", "Client authentication", "select", {
      default: "none",
      choices: ["none", "want", "need"],
      tab: "tls",
    }),
    field("configParams.trustStorePath", "Trust store path", "text", {
      tab: "tls",
      help: "Required when client authentication is want or need.",
    }),
    field(
      "configParams.trustStorePassword",
      "Trust store password",
      "password",
      { tab: "tls" },
    ),
    field(
      "configParams.trustStorePasswordEnv",
      "Trust store password env",
      "text",
      { tab: "tls" },
    ),
    field("configParams.trustStoreType", "Trust store type", "select", {
      default: "PKCS12",
      choices: ["PKCS12", "JKS"],
      tab: "tls",
    }),
    field("configParams.enabledProtocols", "Enabled protocols", "text", {
      default: "TLSv1.3,TLSv1.2",
      tab: "tls",
      help: "Comma-separated protocol names.",
    }),
    field("configParams.tlsAlgorithm", "TLS algorithm", "text", {
      default: "TLS",
      tab: "advanced",
    }),
  ];
}

function rabbitFields(tls: boolean) {
  const fields = [
    field("host", "Host", "text", {
      default: "localhost",
      required: true,
      tab: "connection",
    }),
    field("port", "Port", "number", {
      default: tls ? 5671 : 5672,
      min: 1,
      max: 65535,
      tab: "connection",
    }),
    field("configParams.username", "Username", "text", {
      default: "guest",
      tab: "connection",
    }),
    field("configParams.password", "Password", "password", {
      default: "guest",
      tab: "connection",
    }),
    field("configParams.virtualHost", "Virtual host", "text", {
      default: "/",
      tab: "connection",
    }),
    field("timeoutMs", "Timeout", "number", {
      default: 5000,
      min: 100,
      unit: "ms",
      tab: "connection",
    }),
    field("configParams.charset", "Charset", "text", {
      default: "UTF-8",
      tab: "advanced",
    }),
    field("configParams.queue", "Queue", "text", {
      required: true,
      tab: "subscription",
    }),
    field("configParams.autoAck", "Auto acknowledge", "boolean", {
      default: false,
      tab: "subscription",
    }),
    field("configParams.prefetchCount", "Prefetch count", "number", {
      default: 1,
      min: 1,
      tab: "subscription",
    }),
    field("configParams.declareQueue", "Declare queue", "boolean", {
      default: false,
      tab: "subscription",
    }),
    field("configParams.durableQueue", "Durable queue", "boolean", {
      default: true,
      tab: "subscription",
    }),
    field("configParams.exclusiveQueue", "Exclusive queue", "boolean", {
      default: false,
      tab: "subscription",
    }),
    field("configParams.autoDeleteQueue", "Auto-delete queue", "boolean", {
      default: false,
      tab: "subscription",
    }),
    field("configParams.exchange", "Exchange", "text", { tab: "subscription" }),
    field("configParams.routingKey", "Routing key", "text", {
      default: "",
      tab: "subscription",
    }),
    field("configParams.bindQueue", "Bind queue", "boolean", {
      default: false,
      tab: "subscription",
    }),
  ];
  if (tls) {
    fields.push(
      field("configParams.keyStorePath", "Client key store path", "text", {
        tab: "tls",
      }),
      field(
        "configParams.keyStorePassword",
        "Client key store password",
        "password",
        { tab: "tls" },
      ),
      field(
        "configParams.keyStorePasswordEnv",
        "Client key store password env",
        "text",
        { tab: "tls" },
      ),
      field("configParams.trustStorePath", "Trust store path", "text", {
        tab: "tls",
      }),
      field(
        "configParams.trustStorePassword",
        "Trust store password",
        "password",
        { tab: "tls" },
      ),
      field(
        "configParams.trustStorePasswordEnv",
        "Trust store password env",
        "text",
        { tab: "tls" },
      ),
      field("configParams.keyStoreType", "Key store type", "select", {
        default: "PKCS12",
        choices: ["PKCS12", "JKS"],
        tab: "tls",
      }),
      field("configParams.trustStoreType", "Trust store type", "select", {
        default: "PKCS12",
        choices: ["PKCS12", "JKS"],
        tab: "tls",
      }),
      field("configParams.tlsAlgorithm", "TLS algorithm", "text", {
        default: "TLS",
        tab: "tls",
      }),
      field(
        "configParams.hostnameVerification",
        "Hostname verification",
        "boolean",
        { default: true, tab: "tls" },
      ),
    );
  }
  return fields;
}
