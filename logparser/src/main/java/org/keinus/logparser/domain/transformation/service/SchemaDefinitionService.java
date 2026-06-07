package org.keinus.logparser.domain.transformation.service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.keinus.logparser.interfaces.dto.transform.ColumnMetadataDto;
import org.keinus.logparser.interfaces.dto.transform.SchemaMetadataDto;
import org.springframework.stereotype.Service;

@Service
public class SchemaDefinitionService {

    public SchemaMetadataDto getSchemaMetadata() {
        return SchemaMetadataDto.builder()
                .commonSchema(getCommonColumns())
                .subSchemas(getSubSchemas())
                .build();
    }

    private List<ColumnMetadataDto> getCommonColumns() {
        return Arrays.asList(
            col("event_id", "BIGINT", "Unique Event ID"),
            col("event_time", "TIMESTAMP", "Occurrence Time"),
            col("ingest_time", "TIMESTAMP", "Ingestion Time"),
            col("event_category", "VARCHAR", "Category"),
            col("event_type", "VARCHAR", "Type"),
            col("event_action", "VARCHAR", "Action"),
            col("event_result", "VARCHAR", "Result"),
            col("severity", "SMALLINT", "Severity"),
            col("src_ip", "INET", "Source IP"),
            col("src_port", "INTEGER", "Source Port"),
            col("dst_ip", "INET", "Destination IP"),
            col("dst_port", "INTEGER", "Destination Port"),
            col("protocol", "VARCHAR", "Protocol"),
            col("src_host", "VARCHAR", "Source Host"),
            col("dst_host", "VARCHAR", "Destination Host"),
            col("user_name", "VARCHAR", "User Name"),
            col("user_id", "VARCHAR", "User ID"),
            col("log_source", "VARCHAR", "Log Source"),
            col("raw_log", "TEXT", "Original Log")
        );
    }

    private Map<String, List<ColumnMetadataDto>> getSubSchemas() {
        Map<String, List<ColumnMetadataDto>> map = new HashMap<>();
        
        map.put("event_network", Arrays.asList(
            col("bytes_in", "BIGINT", "Bytes In"),
            col("bytes_out", "BIGINT", "Bytes Out"),
            col("packets_in", "BIGINT", "Packets In"),
            col("packets_out", "BIGINT", "Packets Out"),
            col("direction", "VARCHAR", "Direction"),
            col("session_id", "VARCHAR", "Session ID"),
            col("duration_ms", "BIGINT", "Duration (ms)")
        ));

        map.put("event_web", Arrays.asList(
            col("http_method", "VARCHAR", "HTTP Method"),
            col("uri_path", "TEXT", "URI Path"),
            col("http_status", "INTEGER", "HTTP Status"),
            col("user_agent", "TEXT", "User Agent"),
            col("referer", "TEXT", "Referer"),
            col("bytes", "BIGINT", "Bytes")
        ));
        
        map.put("event_auth", Arrays.asList(
            col("auth_method", "VARCHAR", "Auth Method"),
            col("auth_protocol", "VARCHAR", "Auth Protocol"),
            col("failure_reason", "VARCHAR", "Failure Reason"),
            col("mfa_used", "BOOLEAN", "MFA Used")
        ));

        // Add other schemas as needed based on transform.md...
        // For prototype, these 3 are sufficient to demonstrate.
        
        return map;
    }

    private ColumnMetadataDto col(String name, String type, String desc) {
        return ColumnMetadataDto.builder()
                .name(name)
                .type(type)
                .description(desc)
                .deprecated(false) // Can set true if needed
                .build();
    }
}
