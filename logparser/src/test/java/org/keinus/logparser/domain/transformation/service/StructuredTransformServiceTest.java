package org.keinus.logparser.domain.transformation.service;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredTransformServiceTest {

    @Test
    void mapsNestedSnmpValuesPreservingCounter64AndExactFlatKeyPrecedence() {
        var service = new StructuredTransformService(mock(MappingRepository.class), new ConditionEvaluator(), mock(StructuredEventSerializer.class));
        var config = new org.keinus.logparser.domain.model.mapping.MappingConfiguration();
        config.setCommonMappings(java.util.List.of(new org.keinus.logparser.domain.model.mapping.FieldMapping("metrics.device.value", "src_host", null)));
        var rule = new org.keinus.logparser.domain.model.mapping.SubTableRule();
        rule.setTargetSubTable("event_snmp");
        rule.setMappings(java.util.List.of(
                new org.keinus.logparser.domain.model.mapping.FieldMapping("/metrics/counter.name~1~0/value", "metric_counter", null),
                new org.keinus.logparser.domain.model.mapping.FieldMapping("metrics.temperature.value", "metric_temperature", null),
                new org.keinus.logparser.domain.model.mapping.FieldMapping("metrics.missing.value", "missing", "fallback")));
        config.setSubTableRules(java.util.List.of(rule));
        var metrics = java.util.Map.of("counter.name/~", java.util.Map.of("value", "18446744073709551615"),
                "temperature", java.util.Map.of("value", 0), "device", java.util.Map.of("value", "nested"));
        var event = new LogEvent("{}", "device", "snmp");
        event.setField("metrics", metrics);
        event.setField("metrics.device.value", "flat");
        var result = service.transform(event, config);
        org.assertj.core.api.Assertions.assertThat(result.getCommon().getSrcHost()).isEqualTo("flat");
        org.assertj.core.api.Assertions.assertThat(result.getSubFields()).containsEntry("metric_counter", "18446744073709551615")
                .containsEntry("metric_temperature", 0).containsEntry("missing", "fallback");
        org.assertj.core.api.Assertions.assertThat(result.getAdditionalAttributes()).containsEntry("metrics", metrics);
    }

    @Test
    void reloadClearsMappingAndExpressionCaches() {
        MappingRepository mappingRepository = mock(MappingRepository.class);
        ConditionEvaluator conditionEvaluator = mock(ConditionEvaluator.class);
        StructuredEventSerializer serializer = mock(StructuredEventSerializer.class);

        StructuredTransformService service = new StructuredTransformService(
                mappingRepository,
                conditionEvaluator,
                serializer
        );

        service.reload();

        verify(conditionEvaluator).clearCache();
    }

    @Test
    void invalidateCacheForcesRepositoryReload() {
        MappingRepository mappingRepository = mock(MappingRepository.class);
        ConditionEvaluator conditionEvaluator = mock(ConditionEvaluator.class);
        StructuredEventSerializer serializer = mock(StructuredEventSerializer.class);
        when(mappingRepository.findByMessageType("test"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());

        StructuredTransformService service = new StructuredTransformService(
                mappingRepository,
                conditionEvaluator,
                serializer
        );

        service.transform(new LogEvent("first", "localhost", "test"));
        service.invalidateCache("test");
        service.transform(new LogEvent("second", "localhost", "test"));

        verify(mappingRepository, times(2)).findByMessageType("test");
        verify(conditionEvaluator, times(1)).clearCache();
    }
}
