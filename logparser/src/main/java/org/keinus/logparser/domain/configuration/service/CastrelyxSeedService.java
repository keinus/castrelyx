package org.keinus.logparser.domain.configuration.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.SubTableRule;
import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.ConfigSettingsRepository;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.infrastructure.persistence.repository.OutputAdapterRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CastrelyxSeedService {
    public static final String SEED_MARKER_KEY = "castrelyx.seed.castrelyx-agent-clickhouse.version";
    public static final String MAPPING_SEED_MARKER_KEY = "castrelyx.seed.castrelyx-agent-mapping.version";
    private static final String SEED_MARKER_VALUE = "2";
    private static final String MAPPING_SEED_MARKER_VALUE = "1";
    private static final String MESSAGE_TYPE = "castrelyx-agent-item";
    private static final String INPUT_TYPE = "TcpMtlsGzipInputAdapter";
    private static final String OUTPUT_TYPE = "ClickHouseOutputAdapter";
    private static final String LEGACY_OUTPUT_TYPE = "MariaDbOutputAdapter";

    private static final String INPUT_CONFIG_PARAMS = """
            {"keyStorePath":"/var/lib/castrelsign/certs/server.p12","keyStorePasswordEnv":"CASTRELSIGN_KEYSTORE_PASSWORD","trustStorePath":"/var/lib/castrelsign/certs/truststore.p12","trustStorePasswordEnv":"CASTRELSIGN_KEYSTORE_PASSWORD","maxFrameBytes":10485760,"ackMode":"queueAccepted"}
            """;

    private static final String OUTPUT_CONFIG_PARAMS = """
            {"endpointUrl":"http://clickhouse:8123","database":"castrelyx","usernameEnv":"CLICKHOUSE_USER","passwordEnv":"CLICKHOUSE_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
            """;

    private final InputAdapterRepository inputAdapterRepository;
    private final OutputAdapterRepository outputAdapterRepository;
    private final ConfigSettingsRepository configSettingsRepository;
    private final MappingRepository mappingRepository;

    public void seedDefaults() {
        seedAdapterDefaults();
        seedMappingDefaults();
    }

    private void seedAdapterDefaults() {
        if (configSettingsRepository.findByConfigKey(SEED_MARKER_KEY).isPresent()) {
            log.debug("Castrelyx seed marker already exists");
            return;
        }

        upsertInputSeedRow();
        upsertOutputSeedRow();
        disableLegacyMariaDbOutputSeed();

        configSettingsRepository.save(ConfigSettingsEntity.builder()
                .configKey(SEED_MARKER_KEY)
                .configValue(SEED_MARKER_VALUE)
                .dataType("STRING")
                .description("Castrelyx agent MariaDB seed version")
                .build());
    }

    private void seedMappingDefaults() {
        boolean markerExists = configSettingsRepository.findByConfigKey(MAPPING_SEED_MARKER_KEY).isPresent();

        if (mappingRepository.findByMessageType(MESSAGE_TYPE).isEmpty()) {
            mappingRepository.save(defaultAgentMapping());
            log.info("Seeded Castrelyx agent structured mapping");
        }

        if (!markerExists) {
            configSettingsRepository.save(ConfigSettingsEntity.builder()
                    .configKey(MAPPING_SEED_MARKER_KEY)
                    .configValue(MAPPING_SEED_MARKER_VALUE)
                    .dataType("STRING")
                    .description("Castrelyx agent structured mapping seed version")
                    .build());
        }
    }

    private void upsertInputSeedRow() {
        InputAdapterEntity row = inputAdapterRepository.findByType(INPUT_TYPE).stream()
                .filter(candidate -> MESSAGE_TYPE.equals(candidate.getMessagetype()))
                .findFirst()
                .orElseGet(() -> InputAdapterEntity.builder()
                        .type(INPUT_TYPE)
                        .messagetype(MESSAGE_TYPE)
                        .build());
        row.setHost("0.0.0.0");
        row.setPort(9443);
        row.setEnabled(true);
        row.setConfigParams(INPUT_CONFIG_PARAMS);
        inputAdapterRepository.save(row);
        log.info("Seeded enabled Castrelyx TCP/mTLS gzip input adapter");
    }

    private void upsertOutputSeedRow() {
        OutputAdapterEntity row = outputAdapterRepository.findByType(OUTPUT_TYPE).stream()
                .filter(candidate -> MESSAGE_TYPE.equals(candidate.getMessagetype()))
                .findFirst()
                .orElseGet(() -> OutputAdapterEntity.builder()
                        .type(OUTPUT_TYPE)
                        .messagetype(MESSAGE_TYPE)
                        .build());
        row.setEnabled(true);
        row.setConfigParams(OUTPUT_CONFIG_PARAMS);
        outputAdapterRepository.save(row);
        log.info("Seeded enabled Castrelyx ClickHouse output adapter");
    }

    private void disableLegacyMariaDbOutputSeed() {
        outputAdapterRepository.findByType(LEGACY_OUTPUT_TYPE).stream()
                .filter(row -> MESSAGE_TYPE.equals(row.getMessagetype()))
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .forEach(row -> {
                    row.setEnabled(false);
                    outputAdapterRepository.save(row);
                    log.info("Disabled legacy Castrelyx MariaDB output adapter");
                });
    }

    private MappingConfiguration defaultAgentMapping() {
        MappingConfiguration config = new MappingConfiguration();
        config.setId("castrelyx-agent-item-v1");
        config.setMessageType(MESSAGE_TYPE);
        config.setCommonMappings(List.of(
                new FieldMapping("observed_at", "event_time", null),
                new FieldMapping("payload_observed_at", "event_time", null),
                new FieldMapping("item_kind", "event_category", null),
                new FieldMapping("item_type", "event_type", null),
                new FieldMapping("item_key", "event_action", null),
                new FieldMapping("payload_action", "event_action", null),
                new FieldMapping("payload_outcome", "event_result", null),
                new FieldMapping("payload_protocol", "protocol", null),
                new FieldMapping("payload_actor", "user_name", null),
                new FieldMapping("source_id", "src_host", null),
                new FieldMapping("source", "log_source", null),
                new FieldMapping("payload_source", "log_source", null)
        ));
        config.setSubTableRules(List.of(
                networkBytesRule("['item_kind'] == 'metric' && ['payload_direction'] == 'ingress'", "bytes_in"),
                networkBytesRule("['item_kind'] == 'metric' && ['payload_direction'] == 'egress'", "bytes_out"),
                socketDirectionRule()
        ));
        return config;
    }

    private SubTableRule networkBytesRule(String conditionExpression, String bytesTargetField) {
        SubTableRule rule = new SubTableRule();
        rule.setTargetSubTable("event_network");
        rule.setConditionExpression(conditionExpression);
        rule.setMappings(List.of(
                new FieldMapping("payload_value", bytesTargetField, null),
                new FieldMapping("payload_direction", "direction", null)
        ));
        return rule;
    }

    private SubTableRule socketDirectionRule() {
        SubTableRule rule = new SubTableRule();
        rule.setTargetSubTable("event_network");
        rule.setConditionExpression("['item_type'] == 'socket'");
        rule.setMappings(List.of(
                new FieldMapping("payload_direction", "direction", null)
        ));
        return rule;
    }
}
