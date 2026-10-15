package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.parse.model.JsonParser;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TelemetryTransformServiceTest {
    @Test
    void customInputFieldMappingProducesTheSameMetricContract() {
        var service = new StructuredTransformService(mock(MappingRepository.class), new ConditionEvaluator(),
                new StructuredEventSerializer(new ObjectMapper()), new TelemetryTransformService(
                new TelemetryAssetBindings(Map.of("192.168.50.22", "nas"))));
        var config = new org.keinus.logparser.domain.model.mapping.MappingConfiguration();
        var rule = new org.keinus.logparser.domain.model.mapping.SubTableRule();
        rule.setTargetSubTable("event_metric");
        rule.setMappings(java.util.List.of(
                new org.keinus.logparser.domain.model.mapping.FieldMapping("deviceAddress", "asset_uid", null),
                new org.keinus.logparser.domain.model.mapping.FieldMapping("sensorMetric", "metric_name", null),
                new org.keinus.logparser.domain.model.mapping.FieldMapping("reading", "metric_value", null),
                new org.keinus.logparser.domain.model.mapping.FieldMapping("sensorUnit", "unit", null)));
        config.setSubTableRules(java.util.List.of(rule));
        var event = new LogEvent("{}", "http-collector", "custom-http-input");
        event.setFields(Map.of("deviceAddress", "192.168.50.22", "sensorMetric", "host.temperature.celsius",
                "reading", 42, "sensorUnit", "celsius"));
        assertThat(service.transform(event, config).getTelemetry().metrics()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("asset_uid", "nas")
                        .containsEntry("metric_name", "host.temperature.celsius").containsEntry("metric_value", 42.0)
                        .containsEntry("unit", "celsius"));
    }

    @Test
    void agentAndSnmpUseTheSameStructuredContractAndExistingNasIdentity() throws Exception {
        var json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var normalizer = new TelemetryTransformService(new TelemetryAssetBindings(Map.of("192.168.50.22", "nas")));
        var service = new StructuredTransformService(mock(MappingRepository.class), new ConditionEvaluator(),
                new StructuredEventSerializer(json), normalizer);
        var agent = new LogEvent("{}", "nas", "agent");
        agent.setField("source_id", "nas").setField("item_kind", "metric");
        agent.setField("payload", Map.of("metric_name", "host.network.rx_bytes", "value", 12345,
                "unit", "bytes", "interface", "eth0", "direction", "ingress"));
        var snmp = new LogEvent(json.writeValueAsString(Map.of("protocol", "snmp", "target_host", "192.168.50.22",
                "operation", "poll", "metrics", Map.of("renamedCounter", Map.of(
                "oid", "1.3.6.1.2.1.31.1.1.1.6.1", "value", "12345", "status", "success"),
                "name", Map.of("oid", "1.3.6.1.2.1.31.1.1.1.1.1", "value", "eth0")))), "192.168.50.22", "snmp");
        assertThat(new JsonParser().parse(snmp)).isTrue();
        var agentResult = service.transform(agent).getTelemetry();
        var snmpResult = service.transform(snmp).getTelemetry();
        assertThat(agentResult.metrics()).hasSize(1);
        assertThat(snmpResult.metrics()).hasSize(1);
        assertThat(snmpResult.metrics().getFirst().keySet()).isEqualTo(agentResult.metrics().getFirst().keySet());
        assertThat(snmpResult.metrics().getFirst()).containsEntry("asset_uid", "nas")
                .containsEntry("metric_name", "host.network.rx_bytes").containsEntry("metric_value", 12345.0)
                .containsEntry("unit", "bytes").containsEntry("source_type", "SNMP");
        assertThat(agentResult.metrics().getFirst()).containsEntry("asset_uid", "nas").containsEntry("source_type", "AGENT");
        assertThat(snmpResult.states()).singleElement().satisfies(row -> assertThat(row).containsEntry("asset_uid", "nas"));
        assertThat(service.applyToLogEvent(snmp)).isTrue();
        assertThat(snmp.toOutputJson()).contains("\"telemetry\"", "host.network.rx_bytes", "renamedCounter");
    }

    @Test
    void mappingsAndFailuresStillPassThroughStructuredStage() {
        var normalizer = new TelemetryTransformService(new TelemetryAssetBindings(Map.of()));
        var service = new StructuredTransformService(mock(MappingRepository.class), new ConditionEvaluator(),
                new StructuredEventSerializer(new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())), normalizer);
        var event = new LogEvent("{}", "192.0.2.1", "snmp");
        event.setField("protocol", "snmp").setField("target_host", "192.0.2.1").setField("poll_status", "error");
        var result = service.transform(event).getTelemetry();
        assertThat(result.metrics()).isEmpty();
        assertThat(result.events()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("event_type", "snmp.poll.failure").containsEntry("source_type", "SNMP"));
    }
}
