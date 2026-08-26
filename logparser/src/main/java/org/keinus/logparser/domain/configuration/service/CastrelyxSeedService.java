package org.keinus.logparser.domain.configuration.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CastrelyxSeedService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final String SEED_MARKER_KEY = "castrelyx.seed.castrelyx-agent-clickhouse.version";
    public static final String MAPPING_SEED_MARKER_KEY = "castrelyx.seed.castrelyx-agent-mapping.version";
    private static final String SEED_MARKER_VALUE = "6";
    private static final String MAPPING_SEED_MARKER_VALUE = "1";
    private static final String MESSAGE_TYPE = "castrelyx-agent-item";
    private static final String INPUT_TYPE = "TcpMtlsGzipInputAdapter";
    private static final String OUTPUT_TYPE = "ClickHouseOutputAdapter";

    private static final Path LEGACY_CONTAINER_DATA_DIR = Path.of("/var/lib/castrelsign");
    private static final String LEGACY_KEY_STORE_PATH = "/var/lib/castrelsign/certs/server.p12";
    private static final String LEGACY_TRUST_STORE_PATH = "/var/lib/castrelsign/certs/truststore.p12";
    private static final String CASTRELSIGN_DATA_DIR_ENV = "CASTRELSIGN_DATA_DIR";

    private static final String OUTPUT_CONFIG_PARAMS = """
            {"endpointUrl":"http://clickhouse:8123","database":"castrelyx","usernameEnv":"CLICKHOUSE_USER","passwordEnv":"CLICKHOUSE_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"incompleteGroupTimeoutMs":30000,"maxPendingGroups":2048,"maxPendingItems":50000,"maxPendingBytes":67108864,"incompleteChunkDlqDir":"/root/logparser/data/incomplete-chunks","maxIncompleteChunkDlqBytes":134217728,"maxIncompleteChunkDlqRecords":1000,"autoCreateSchema":true}
            """;
    private static final String INPUT_MIGRATION_DEFAULTS = """
            {"maxConnections":32,"tlsReloadIntervalMs":5000}
            """;
    private static final String OUTPUT_MIGRATION_DEFAULTS = """
            {"maxPendingBytes":67108864,"incompleteChunkDlqDir":"/root/logparser/data/incomplete-chunks","maxIncompleteChunkDlqBytes":134217728,"maxIncompleteChunkDlqRecords":1000}
            """;

    private final InputAdapterRepository inputAdapterRepository;
    private final OutputAdapterRepository outputAdapterRepository;
    private final ConfigSettingsRepository configSettingsRepository;
    private final MappingRepository mappingRepository;

    @Transactional
    public void seedDefaults() {
        seedAdapterDefaults();
        seedMappingDefaults();
    }

    private void seedAdapterDefaults() {
        var existingMarker = configSettingsRepository.findByConfigKey(SEED_MARKER_KEY);
        if (existingMarker.map(ConfigSettingsEntity::getConfigValue).filter(SEED_MARKER_VALUE::equals).isPresent()) {
            log.debug("Castrelyx adapter seed is already at version {}", SEED_MARKER_VALUE);
            return;
        }

        upsertInputSeedRow();
        upsertOutputSeedRow();

        ConfigSettingsEntity marker = existingMarker.orElseGet(ConfigSettingsEntity::new);
        marker.setConfigKey(SEED_MARKER_KEY);
        marker.setConfigValue(SEED_MARKER_VALUE);
        marker.setDataType("STRING");
        marker.setDescription("Castrelyx agent ClickHouse adapter seed version");
        configSettingsRepository.save(marker);
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
        InputAdapterEntity existing = inputAdapterRepository.findByType(INPUT_TYPE).stream()
                .filter(candidate -> MESSAGE_TYPE.equals(candidate.getMessagetype()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            InputAdapterEntity created = InputAdapterEntity.builder()
                    .type(INPUT_TYPE)
                    .messagetype(MESSAGE_TYPE)
                    .host("0.0.0.0")
                    .port(9443)
                    .enabled(true)
                    .configParams(defaultInputConfigParams(resolveCastrelyxDataDir()))
                    .build();
            inputAdapterRepository.save(created);
            log.info("Seeded enabled Castrelyx TCP/mTLS gzip input adapter");
            return;
        }

        String merged = mergeMissingConfig(existing.getConfigParams(), INPUT_MIGRATION_DEFAULTS, INPUT_TYPE);
        merged = migrateLegacyInputPaths(merged, resolveCastrelyxDataDir());
        if (!merged.equals(existing.getConfigParams())) {
            existing.setConfigParams(merged);
            inputAdapterRepository.save(existing);
            log.info("Updated Castrelyx TCP/mTLS defaults without replacing operator settings");
        }
    }

    private void upsertOutputSeedRow() {
        OutputAdapterEntity existing = outputAdapterRepository.findByType(OUTPUT_TYPE).stream()
                .filter(candidate -> MESSAGE_TYPE.equals(candidate.getMessagetype()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            OutputAdapterEntity created = OutputAdapterEntity.builder()
                    .type(OUTPUT_TYPE)
                    .messagetype(MESSAGE_TYPE)
                    .enabled(true)
                    .configParams(OUTPUT_CONFIG_PARAMS)
                    .build();
            outputAdapterRepository.save(created);
            log.info("Seeded enabled Castrelyx ClickHouse output adapter");
            return;
        }

        String merged = mergeMissingConfig(existing.getConfigParams(), OUTPUT_MIGRATION_DEFAULTS, OUTPUT_TYPE);
        if (!merged.equals(existing.getConfigParams())) {
            existing.setConfigParams(merged);
            outputAdapterRepository.save(existing);
            log.info("Added missing ClickHouse buffering defaults without replacing operator settings");
        }
    }

    private String mergeMissingConfig(String currentConfig, String missingDefaults, String adapterType) {
        if (currentConfig == null || currentConfig.isBlank()) {
            throw new IllegalStateException(adapterType + " existing configParams is blank; refusing destructive seed replacement");
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(currentConfig);
            JsonNode defaults = OBJECT_MAPPER.readTree(missingDefaults);
            if (!(parsed instanceof ObjectNode current) || !defaults.isObject()) {
                throw new IllegalStateException(adapterType + " configParams must be a JSON object");
            }
            boolean changed = false;
            var fields = defaults.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!current.has(field.getKey())) {
                    current.set(field.getKey(), field.getValue());
                    changed = true;
                }
            }
            return changed ? OBJECT_MAPPER.writeValueAsString(current) : currentConfig;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(adapterType + " configParams is invalid; refusing destructive seed replacement", e);
        }
    }

    static Path resolveCastrelyxDataDir() {
        String configuredDataDir = System.getenv(CASTRELSIGN_DATA_DIR_ENV);
        if (configuredDataDir != null && !configuredDataDir.isBlank()) {
            return Path.of(configuredDataDir).toAbsolutePath().normalize();
        }
        if (Files.isDirectory(LEGACY_CONTAINER_DATA_DIR)) {
            return LEGACY_CONTAINER_DATA_DIR;
        }
        return Path.of(System.getProperty("user.home"), "castrelsign").toAbsolutePath().normalize();
    }

    static String defaultInputConfigParams(Path dataDir) {
        ObjectNode config = OBJECT_MAPPER.createObjectNode();
        Path certDir = dataDir.resolve("certs");
        config.put("keyStorePath", certDir.resolve("server.p12").toString());
        config.put("keyStorePasswordEnv", "CASTRELSIGN_KEYSTORE_PASSWORD");
        config.put("trustStorePath", certDir.resolve("truststore.p12").toString());
        config.put("trustStorePasswordEnv", "CASTRELSIGN_KEYSTORE_PASSWORD");
        config.put("maxFrameBytes", 10_485_760);
        config.put("maxConnections", 32);
        config.put("tlsReloadIntervalMs", 5_000);
        config.put("ackMode", "queueAccepted");
        return config.toString();
    }

    static String migrateLegacyInputPaths(String currentConfig, Path resolvedDataDir) {
        Path normalizedDataDir = resolvedDataDir.toAbsolutePath().normalize();
        if (LEGACY_CONTAINER_DATA_DIR.equals(normalizedDataDir)) {
            return currentConfig;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(currentConfig);
            if (!(parsed instanceof ObjectNode current)) {
                throw new IllegalStateException(INPUT_TYPE + " configParams must be a JSON object");
            }
            Path certDir = normalizedDataDir.resolve("certs");
            boolean changed = replaceLegacyPath(
                    current,
                    "keyStorePath",
                    LEGACY_KEY_STORE_PATH,
                    certDir.resolve("server.p12"));
            changed |= replaceLegacyPath(
                    current,
                    "trustStorePath",
                    LEGACY_TRUST_STORE_PATH,
                    certDir.resolve("truststore.p12"));
            return changed ? OBJECT_MAPPER.writeValueAsString(current) : currentConfig;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(INPUT_TYPE + " configParams is invalid; refusing path migration", e);
        }
    }

    private static boolean replaceLegacyPath(
            ObjectNode config,
            String fieldName,
            String legacyPath,
            Path replacementPath) {
        String currentValue = config.path(fieldName).asText("");
        if (!legacyPath.equals(currentValue)) {
            return false;
        }
        config.put(fieldName, replacementPath.toString());
        return true;
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
