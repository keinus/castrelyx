package org.keinus.logparser.domain.configuration.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
@Slf4j
public class ConfigMetadataService {

    // ==================== Adapter Type Information ====================

    public List<AdapterTypeInfo> getInputAdapterTypes() {
        return Arrays.asList(
                new AdapterTypeInfo("TcpInputAdapter", "TCP Input", "Listen for TCP connections"),
                new AdapterTypeInfo("UdpInputAdapter", "UDP Input", "Listen for UDP datagrams"),
                new AdapterTypeInfo("HttpInputAdapter", "HTTP Input", "HTTP REST endpoint"),
                new AdapterTypeInfo("KafkaInputAdapter", "Kafka Input", "Consume from Kafka topic"),
                new AdapterTypeInfo("SnmpInputAdapter", "SNMP Collector", "Poll SNMP v1/v2c/v3 targets"),
                new AdapterTypeInfo("RabbitMqInputAdapter", "RabbitMQ Input", "Consume from RabbitMQ queue"),
                new AdapterTypeInfo("TcpMtlsGzipInputAdapter", "Castrelyx TCP mTLS Gzip Input", "Receive gzip batches over TCP/mTLS"),
                new AdapterTypeInfo("FileInputAdapter", "File Input", "Read from files"),
                new AdapterTypeInfo("FakeInputAdapter", "Fake Input", "Generate test data")
        );
    }

    public List<AdapterTypeInfo> getOutputAdapterTypes() {
        return Arrays.asList(
                new AdapterTypeInfo("ConsoleOutputAdapter", "Console Output", "Print to console"),
                new AdapterTypeInfo("TcpOutputAdapter", "TCP Output", "Send via TCP"),
                new AdapterTypeInfo("HttpOutputAdapter", "HTTP Output", "Send via HTTP POST/PUT"),
                new AdapterTypeInfo("KafkaOutputAdapter", "Kafka Output", "Produce to Kafka topic"),
                new AdapterTypeInfo("OpenSearchOutputAdapter", "OpenSearch Output", "Index to OpenSearch/Elasticsearch"),
                new AdapterTypeInfo("RabbitMQAdapter", "RabbitMQ Output", "Publish to RabbitMQ exchange"),
                new AdapterTypeInfo("MariaDbOutputAdapter", "MariaDB Output", "Persist Castrelyx agent events to MariaDB"),
                new AdapterTypeInfo("ClickHouseOutputAdapter", "ClickHouse Output", "Persist Castrelyx agent events to ClickHouse"),
                new AdapterTypeInfo("BenchmarkAdapter", "Benchmark Output", "Performance testing")
        );
    }

    public List<AdapterTypeInfo> getParserTypes() {
        return Arrays.asList(
                new AdapterTypeInfo("JsonParser", "JSON Parser", "Parse JSON formatted logs"),
                new AdapterTypeInfo("GrokParser", "Grok Parser", "Parse with Grok patterns"),
                new AdapterTypeInfo("RegexParser", "Regex Parser", "Parse with regular expressions"),
                new AdapterTypeInfo("RFC3164SyslogParser", "RFC3164 Syslog Parser", "Parse RFC3164 syslog format"),
                new AdapterTypeInfo("RFC5424SyslogParser", "RFC5424 Syslog Parser", "Parse RFC5424 syslog format"),
                new AdapterTypeInfo("HttpParser", "HTTP Parser", "Parse HTTP access logs")
        );
    }

    public List<TransformTypeInfo> getTransformTypes() {
        return Arrays.asList(
                new TransformTypeInfo("Filter", "Filter Transform", "Filter messages based on conditions"),
                new TransformTypeInfo("AddProperty", "Add Property", "Add fields to messages"),
                new TransformTypeInfo("RemoveProperty", "Remove Property", "Remove fields from messages")
        );
    }

    // ==================== Schema Information ====================

    public AdapterSchema getInputAdapterSchema(String type) {
        return switch (type) {
            case "TcpInputAdapter", "UdpInputAdapter" -> new AdapterSchema(
                    type,
                    List.of(
                            new FieldSchema("port", "Integer", true, "Port to listen on")
                    )
            );
            case "HttpInputAdapter" -> new AdapterSchema(
                    type,
                    List.of(
                            new FieldSchema("port", "Integer", true, "Port to listen on"),
                            new FieldSchema("path_pattern", "String", false, "HTTP path pattern (default: /)")
                    )
            );
            case "KafkaInputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("bootstrapservers", "String", true, "Kafka bootstrap servers"),
                            new FieldSchema("topicid", "String", true, "Topic to consume from"),
                            new FieldSchema("groupId", "String", false, "Consumer group ID")
                    )
            );
            case "SnmpInputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("configParams", "String", true, "SNMP v1/v2c/v3 collector JSON configuration"),
                            new FieldSchema("timeoutMs", "Integer", false, "SNMP request timeout"),
                            new FieldSchema("queueSize", "Integer", false, "Internal event queue size"),
                            new FieldSchema("workerThreads", "Integer", false, "Concurrent polling workers")
                    )
            );
            case "RabbitMqInputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("host", "String", false, "RabbitMQ host"),
                            new FieldSchema("port", "Integer", false, "RabbitMQ port"),
                            new FieldSchema("configParams", "String", true, "RabbitMQ input JSON configuration"),
                            new FieldSchema("timeoutMs", "Integer", false, "RabbitMQ connection timeout")
                    )
            );
            case "TcpMtlsGzipInputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("port", "Integer", true, "Port to listen on"),
                            new FieldSchema("configParams", "String", true, "TCP mTLS gzip JSON configuration")
                    )
            );
            case "FileInputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("path", "String", true, "File path"),
                            new FieldSchema("isFromBeginning", "Boolean", false, "Read from beginning")
                    )
            );
            default -> new AdapterSchema(type, List.of());
        };
    }

    public AdapterSchema getOutputAdapterSchema(String type) {
        return switch (type) {
            case "TcpOutputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("host", "String", true, "Destination host"),
                            new FieldSchema("port", "Integer", true, "Destination port"),
                            new FieldSchema("timeoutMs", "Integer", false, "Connection timeout")
                    )
            );
            case "HttpOutputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("url", "String", true, "Target URL"),
                            new FieldSchema("method", "String", false, "HTTP method (POST, PUT)"),
                            new FieldSchema("headers", "Map", false, "HTTP headers")
                    )
            );
            case "KafkaOutputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("bootstrapservers", "String", true, "Kafka bootstrap servers"),
                            new FieldSchema("topicid", "String", true, "Topic to produce to"),
                            new FieldSchema("key", "String", false, "Message key")
                    )
            );
            case "OpenSearchOutputAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("url", "String", true, "OpenSearch URL (http://host:port)"),
                            new FieldSchema("index", "String", true, "Index name template"),
                            new FieldSchema("osUsername", "String", false, "Username"),
                            new FieldSchema("osPassword", "String", false, "Password"),
                            new FieldSchema("action", "String", false, "Action (index, create, etc.)")
                    )
            );
            case "RabbitMQAdapter" -> new AdapterSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("host", "String", true, "RabbitMQ host"),
                            new FieldSchema("rmqPort", "Integer", false, "RabbitMQ port"),
                            new FieldSchema("exchange", "String", true, "Exchange name"),
                            new FieldSchema("routingkey", "String", true, "Routing key"),
                            new FieldSchema("rmqUsername", "String", false, "Username"),
                            new FieldSchema("rmqPassword", "String", false, "Password"),
                            new FieldSchema("tagpass", "Map", false, "Tag filtering")
                    )
            );
            case "MariaDbOutputAdapter" -> new AdapterSchema(
                    type,
                    List.of(
                            new FieldSchema("configParams", "String", true, "MariaDB output JSON configuration")
                    )
            );
            case "ClickHouseOutputAdapter" -> new AdapterSchema(
                    type,
                    List.of(
                            new FieldSchema("configParams", "String", true, "ClickHouse output JSON configuration")
                    )
            );
            default -> new AdapterSchema(type, List.of());
        };
    }

    public AdapterSchema getParserSchema(String type) {
        return switch (type) {
            case "GrokParser", "RegexParser" -> new AdapterSchema(
                    type,
                    List.of(
                            new FieldSchema("param", "String", true, "Pattern to match")
                    )
            );
            default -> new AdapterSchema(type, List.of());
        };
    }

    public TransformSchema getTransformSchema(String type) {
        return switch (type) {
            case "Filter" -> new TransformSchema(
                    type,
                    Arrays.asList(
                            new FieldSchema("pass", "Map", false, "Pass conditions (Map)"),
                            new FieldSchema("drop", "Map", false, "Drop conditions (Map)")
                    )
            );
            case "AddProperty" -> new TransformSchema(
                    type,
                    List.of(
                            new FieldSchema("add", "Map", true, "Properties to add (Map<String, List>)")
                    )
            );
            case "RemoveProperty" -> new TransformSchema(
                    type,
                    List.of(
                            new FieldSchema("remove", "List", true, "Properties to remove (List)")
                    )
            );
            default -> new TransformSchema(type, List.of());
        };
    }

    // ==================== Supported Options ====================

    public List<String> getSupportedCodecs() {
        return Arrays.asList("plain", "json", "line");
    }

    public List<String> getSupportedHttpMethods() {
        return Arrays.asList("POST", "PUT", "PATCH");
    }

    // ==================== Inner Classes ====================

    public record AdapterTypeInfo(String type, String displayName, String description) {}

    public record TransformTypeInfo(String type, String displayName, String description) {}

    public record AdapterSchema(String type, List<FieldSchema> fields) {}

    public record TransformSchema(String type, List<FieldSchema> fields) {}

    public record FieldSchema(
            String name,
            String dataType,
            boolean required,
            String description
    ) {}
}
