package org.keinus.logparser.domain.service.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.SubTableRule;
import org.keinus.logparser.domain.transformation.service.ConditionEvaluator;
import org.keinus.logparser.domain.transformation.service.StructuredEventSerializer;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
public class StructuredTransformIntegrationTest {

    @Mock
    private MappingRepository mappingRepository;
    
    private StructuredTransformService transformService;
    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        objectMapper.registerModule(new JavaTimeModule());
        ConditionEvaluator evaluator = new ConditionEvaluator();
        StructuredEventSerializer serializer = new StructuredEventSerializer(objectMapper);
        
        transformService = new StructuredTransformService(mappingRepository, evaluator, serializer);
    }

    @Test
    void testApplyToLogEvent() {
        // 1. Setup Mock Config
        MappingConfiguration config = new MappingConfiguration();
        config.setMessageType("syslog");
        config.setCommonMappings(Arrays.asList(
            new FieldMapping("src", "src_ip", null),
            new FieldMapping("dst", "dst_ip", null)
        ));
        
        SubTableRule rule = new SubTableRule();
        rule.setTargetSubTable("event_network");
        rule.setConditionExpression("['dst_port'] == 80"); // Should match
        rule.setMappings(Arrays.asList(
            new FieldMapping("bytes", "bytes_in", "0")
        ));
        config.setSubTableRules(Arrays.asList(rule));

        when(mappingRepository.findByMessageType("syslog")).thenReturn(Optional.of(config));

        // 2. Create LogEvent
        LogEvent event = new LogEvent("original log", "localhost", "syslog");
        Map<String, Object> fields = new HashMap<>();
        fields.put("src", "192.168.1.1");
        fields.put("dst", "10.0.0.1");
        fields.put("dst_port", 80); // Triggers condition
        fields.put("bytes", 1024);
        fields.put("extra", "foo"); // Unmapped
        event.setFields(fields);

        // 3. Execute
        boolean success = transformService.applyToLogEvent(event);

        // 4. Verify
        assertTrue(success);
        assertTrue(event.isTransformed());
        
        Map<String, Object> resultFields = event.getFields();
        System.out.println("Result Fields: " + resultFields);

        // Check Common
        Object commonValue = resultFields.get("common");
        assertTrue(commonValue instanceof Map<?, ?>);
        Map<?, ?> common = (Map<?, ?>) commonValue;
        assertNotNull(common);
        assertEquals("192.168.1.1", common.get("srcIp"));
        
        // Check SubFields
        assertEquals("event_network", resultFields.get("subDomainType"));
        Object subValue = resultFields.get("subFields");
        assertTrue(subValue instanceof Map<?, ?>);
        Map<?, ?> sub = (Map<?, ?>) subValue;
        assertNotNull(sub, "SubFields is null");
        Object bytesVal = sub.get("bytes_in");
        assertNotNull(bytesVal, "bytes_in is null");
        assertEquals(1024, ((Number)bytesVal).intValue());
        
        // Check Additional Attributes
        Object additionalValue = resultFields.get("additionalAttributes");
        assertTrue(additionalValue instanceof Map<?, ?>);
        Map<?, ?> additional = (Map<?, ?>) additionalValue;
        System.out.println("Additional Attributes: " + additional);
        assertEquals("foo", additional.get("extra"));
        
        assertTrue(additional.containsKey("dst_port"), "additionalAttributes should contain 'dst_port'. Actual keys: " + additional.keySet());
    }

    @Test
    void testCachingBehavior() {
        // 1. Setup Mock Config
        MappingConfiguration config = new MappingConfiguration();
        config.setMessageType("cache_test");
        config.setCommonMappings(Arrays.asList());
        config.setSubTableRules(Arrays.asList());

        when(mappingRepository.findByMessageType("cache_test")).thenReturn(Optional.of(config));

        // 2. Create LogEvent
        LogEvent event1 = new LogEvent("log1", "localhost", "cache_test");
        LogEvent event2 = new LogEvent("log2", "localhost", "cache_test");

        // 3. Execute First Time
        transformService.applyToLogEvent(event1);

        // 4. Execute Second Time
        transformService.applyToLogEvent(event2);

        // 5. Verify Repository was called ONLY ONCE
        org.mockito.Mockito.verify(mappingRepository, org.mockito.Mockito.times(1)).findByMessageType("cache_test");

        // 6. Reload
        transformService.reload();

        // 7. Execute Third Time
        LogEvent event3 = new LogEvent("log3", "localhost", "cache_test");
        transformService.applyToLogEvent(event3);

        // 8. Verify Repository was called TWICE (1 initial + 1 after reload)
        org.mockito.Mockito.verify(mappingRepository, org.mockito.Mockito.times(2)).findByMessageType("cache_test");
    }
}
