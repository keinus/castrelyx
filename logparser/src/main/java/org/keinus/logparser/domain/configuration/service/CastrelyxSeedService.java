package org.keinus.logparser.domain.configuration.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keinus.logparser.infrastructure.persistence.entity.ConfigSettingsEntity;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.repository.ConfigSettingsRepository;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.OutputAdapterRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CastrelyxSeedService {
    public static final String SEED_MARKER_KEY = "castrelyx.seed.castrelyx-agent-mariadb.version";
    private static final String SEED_MARKER_VALUE = "1";
    private static final String MESSAGE_TYPE = "castrelyx-agent-item";
    private static final String INPUT_TYPE = "TcpMtlsGzipInputAdapter";
    private static final String OUTPUT_TYPE = "MariaDbOutputAdapter";

    private static final String INPUT_CONFIG_PARAMS = """
            {"keyStorePath":"/app/certs/logparser-server.p12","keyStorePasswordEnv":"LOGPARSER_KEYSTORE_PASSWORD","trustStorePath":"/app/certs/agent-truststore.p12","trustStorePasswordEnv":"LOGPARSER_TRUSTSTORE_PASSWORD","maxFrameBytes":10485760,"ackMode":"queueAccepted"}
            """;

    private static final String OUTPUT_CONFIG_PARAMS = """
            {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
            """;

    private final InputAdapterRepository inputAdapterRepository;
    private final OutputAdapterRepository outputAdapterRepository;
    private final ConfigSettingsRepository configSettingsRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        seedDefaults();
    }

    @Transactional
    public void seedDefaults() {
        if (configSettingsRepository.findByConfigKey(SEED_MARKER_KEY).isPresent()) {
            log.debug("Castrelyx seed marker already exists");
            return;
        }

        if (!hasInputSeedRow()) {
            inputAdapterRepository.save(InputAdapterEntity.builder()
                    .type(INPUT_TYPE)
                    .messagetype(MESSAGE_TYPE)
                    .host("0.0.0.0")
                    .port(9443)
                    .enabled(false)
                    .configParams(INPUT_CONFIG_PARAMS)
                    .build());
            log.info("Seeded disabled Castrelyx TCP/mTLS gzip input adapter");
        }

        if (!hasOutputSeedRow()) {
            outputAdapterRepository.save(OutputAdapterEntity.builder()
                    .type(OUTPUT_TYPE)
                    .messagetype(MESSAGE_TYPE)
                    .enabled(false)
                    .configParams(OUTPUT_CONFIG_PARAMS)
                    .build());
            log.info("Seeded disabled Castrelyx MariaDB output adapter");
        }

        configSettingsRepository.save(ConfigSettingsEntity.builder()
                .configKey(SEED_MARKER_KEY)
                .configValue(SEED_MARKER_VALUE)
                .dataType("STRING")
                .description("Castrelyx agent MariaDB seed version")
                .build());
    }

    private boolean hasInputSeedRow() {
        return inputAdapterRepository.findByType(INPUT_TYPE).stream()
                .anyMatch(row -> MESSAGE_TYPE.equals(row.getMessagetype()));
    }

    private boolean hasOutputSeedRow() {
        return outputAdapterRepository.findByType(OUTPUT_TYPE).stream()
                .anyMatch(row -> MESSAGE_TYPE.equals(row.getMessagetype()));
    }
}
