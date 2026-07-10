package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.persistence.repository.InputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.OutputAdapterRepository;
import org.keinus.logparser.infrastructure.persistence.repository.ParserRepository;
import org.keinus.logparser.infrastructure.persistence.repository.TransformRepository;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigValidationServiceTest {

    private InputAdapterRepository inputAdapterRepository;
    private ParserRepository parserRepository;
    private TransformRepository transformRepository;
    private OutputAdapterRepository outputAdapterRepository;
    private ConfigValidationService configValidationService;

    @BeforeEach
    void setUp() {
        inputAdapterRepository = mock(InputAdapterRepository.class);
        parserRepository = mock(ParserRepository.class);
        transformRepository = mock(TransformRepository.class);
        outputAdapterRepository = mock(OutputAdapterRepository.class);
        configValidationService = new ConfigValidationService(
                inputAdapterRepository,
                parserRepository,
                transformRepository,
                outputAdapterRepository
        );
    }

    @Test
    void globalOutputAdapterSatisfiesParserOutputRequirement() {
        InputAdapterEntity input = InputAdapterEntity.builder()
                .messagetype("access")
                .enabled(true)
                .build();
        ParserEntity parser = ParserEntity.builder()
                .messagetype("access")
                .enabled(true)
                .build();
        OutputAdapterEntity globalOutput = OutputAdapterEntity.builder()
                .messagetype("all")
                .enabled(true)
                .build();

        when(inputAdapterRepository.findAll()).thenReturn(List.of(input));
        when(parserRepository.findAll()).thenReturn(List.of(parser));
        when(outputAdapterRepository.findAll()).thenReturn(List.of(globalOutput));

        ConfigValidationService.PipelineIntegrityResult result = configValidationService.validatePipelineIntegrity();

        assertTrue(result.warnings().stream().noneMatch(warning -> warning.contains("has no corresponding output adapter")));
        assertTrue(result.warnings().stream().noneMatch(warning -> warning.contains("Output message type 'all'")));
    }

    @Test
    void snmpInputAdapterRequiresConfigParams() {
        InputAdapterEntity missingParams = InputAdapterEntity.builder()
                .type("SnmpInputAdapter")
                .messagetype("snmp-metrics")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingResult =
                configValidationService.validateInputAdapter(missingParams);

        assertFalse(missingResult.isValid());
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("configParams")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("SnmpInputAdapter")
                .messagetype("snmp-metrics")
                .configParams("""
                        {"targets":[{"host":"192.0.2.10","community":"public"}],"oids":["1.3.6.1.2.1.1.5.0"]}
                        """)
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult validResult =
                configValidationService.validateInputAdapter(valid);

        assertTrue(validResult.isValid());
    }

    @Test
    void snmpV3InputAdapterRequiresSecurityConfig() {
        InputAdapterEntity missingSecurity = InputAdapterEntity.builder()
                .type("SnmpInputAdapter")
                .messagetype("snmp-v3-metrics")
                .configParams("""
                        {"targets":[{"host":"192.0.2.20","version":"3"}],"oids":["1.3.6.1.2.1.1.5.0"]}
                        """)
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingResult =
                configValidationService.validateInputAdapter(missingSecurity);

        assertFalse(missingResult.isValid());
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("securityName")));
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("authPassphrase")));
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("privPassphrase")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("SnmpInputAdapter")
                .messagetype("snmp-v3-metrics")
                .configParams("""
                        {"targets":[{"host":"192.0.2.20","version":"3","securityName":"poller","securityLevel":"authPriv","authProtocol":"SHA256","authPassphrase":"auth-secret","privProtocol":"AES128","privPassphrase":"priv-secret"}],"oids":["1.3.6.1.2.1.1.5.0"]}
                        """)
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult validResult =
                configValidationService.validateInputAdapter(valid);

        assertTrue(validResult.isValid());
    }

    @Test
    void rabbitMqInputAdapterRequiresQueueConfigParam() {
        InputAdapterEntity missingParams = InputAdapterEntity.builder()
                .type("RabbitMqInputAdapter")
                .messagetype("rabbit-logs")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingResult =
                configValidationService.validateInputAdapter(missingParams);

        assertFalse(missingResult.isValid());
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("configParams")));

        InputAdapterEntity missingQueue = InputAdapterEntity.builder()
                .type("RabbitMqInputAdapter")
                .messagetype("rabbit-logs")
                .configParams("{\"host\":\"rabbit.local\"}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingQueueResult =
                configValidationService.validateInputAdapter(missingQueue);

        assertFalse(missingQueueResult.isValid());
        assertTrue(missingQueueResult.errors().stream().anyMatch(error -> error.contains("queue")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("RabbitMqInputAdapter")
                .messagetype("rabbit-logs")
                .host("rabbit.local")
                .port(5672)
                .configParams("{\"queue\":\"logs.input\"}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult validResult =
                configValidationService.validateInputAdapter(valid);

        assertTrue(validResult.isValid());
    }

    @Test
    void tlsServerInputAdaptersRequirePortAndKeyStoreConfig() {
        InputAdapterEntity missingParams = InputAdapterEntity.builder()
                .type("HttpsInputAdapter")
                .messagetype("https-logs")
                .port(9443)
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingParamsResult =
                configValidationService.validateInputAdapter(missingParams);

        assertFalse(missingParamsResult.isValid());
        assertTrue(missingParamsResult.errors().stream().anyMatch(error -> error.contains("configParams")));

        InputAdapterEntity missingKeyStore = InputAdapterEntity.builder()
                .type("TlsTcpInputAdapter")
                .messagetype("tls-tcp-logs")
                .port(6514)
                .configParams("{}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingKeyStoreResult =
                configValidationService.validateInputAdapter(missingKeyStore);

        assertFalse(missingKeyStoreResult.isValid());
        assertTrue(missingKeyStoreResult.errors().stream().anyMatch(error -> error.contains("keyStorePath")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("TlsTcpInputAdapter")
                .messagetype("tls-tcp-logs")
                .port(6514)
                .configParams("""
                        {"keyStorePath":"/app/certs/logparser-server.p12","keyStorePasswordEnv":"LOGPARSER_KEYSTORE_PASSWORD","clientAuth":"none"}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateInputAdapter(valid).isValid());
    }

    @Test
    void tlsRabbitMqInputAdapterDefaultsToTlsAndRequiresQueue() {
        InputAdapterEntity missingQueue = InputAdapterEntity.builder()
                .type("TlsRabbitMqInputAdapter")
                .messagetype("rabbit-tls-logs")
                .host("rabbit.local")
                .port(5671)
                .configParams("{\"tlsEnabled\":true}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingQueueResult =
                configValidationService.validateInputAdapter(missingQueue);

        assertFalse(missingQueueResult.isValid());
        assertTrue(missingQueueResult.errors().stream().anyMatch(error -> error.contains("queue")));

        InputAdapterEntity tlsDisabled = InputAdapterEntity.builder()
                .type("TlsRabbitMqInputAdapter")
                .messagetype("rabbit-tls-logs")
                .host("rabbit.local")
                .port(5671)
                .configParams("{\"queue\":\"logs.input\",\"tlsEnabled\":false}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult tlsDisabledResult =
                configValidationService.validateInputAdapter(tlsDisabled);

        assertFalse(tlsDisabledResult.isValid());
        assertTrue(tlsDisabledResult.errors().stream().anyMatch(error -> error.contains("tlsEnabled")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("TlsRabbitMqInputAdapter")
                .messagetype("rabbit-tls-logs")
                .host("rabbit.local")
                .configParams("{\"queue\":\"logs.input\",\"hostnameVerification\":true}")
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateInputAdapter(valid).isValid());
    }

    @Test
    void tcpMtlsGzipInputAdapterRequiresPortAndTlsConfigParams() {
        InputAdapterEntity missingPort = InputAdapterEntity.builder()
                .type("TcpMtlsGzipInputAdapter")
                .messagetype("castrelyx-agent-item")
                .configParams("{}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingPortResult =
                configValidationService.validateInputAdapter(missingPort);

        assertFalse(missingPortResult.isValid());
        assertTrue(missingPortResult.errors().stream().anyMatch(error -> error.contains("Port")));

        InputAdapterEntity missingTlsConfig = InputAdapterEntity.builder()
                .type("TcpMtlsGzipInputAdapter")
                .messagetype("castrelyx-agent-item")
                .port(9443)
                .configParams("{}")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingTlsConfigResult =
                configValidationService.validateInputAdapter(missingTlsConfig);

        assertFalse(missingTlsConfigResult.isValid());
        assertTrue(missingTlsConfigResult.errors().stream().anyMatch(error -> error.contains("keyStorePath")));

        InputAdapterEntity valid = InputAdapterEntity.builder()
                .type("TcpMtlsGzipInputAdapter")
                .messagetype("castrelyx-agent-item")
                .port(9443)
                .configParams("""
                        {"keyStorePath":"/app/certs/logparser-server.p12","keyStorePasswordEnv":"LOGPARSER_KEYSTORE_PASSWORD","trustStorePath":"/app/certs/agent-truststore.p12","trustStorePasswordEnv":"LOGPARSER_TRUSTSTORE_PASSWORD","maxFrameBytes":10485760,"maxConnections":32,"tlsReloadIntervalMs":5000,"ackMode":"queueAccepted"}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateInputAdapter(valid).isValid());

        InputAdapterEntity invalidLimits = InputAdapterEntity.builder()
                .type("TcpMtlsGzipInputAdapter")
                .messagetype("castrelyx-agent-item")
                .port(9443)
                .configParams("""
                        {"keyStorePath":"/app/server.p12","keyStorePasswordEnv":"KEY_PASSWORD","trustStorePath":"/app/trust.p12","trustStorePasswordEnv":"TRUST_PASSWORD","maxConnections":0,"tlsReloadIntervalMs":0}
                        """)
                .enabled(true)
                .build();
        ConfigValidationService.ValidationResult invalidLimitsResult =
                configValidationService.validateInputAdapter(invalidLimits);
        assertFalse(invalidLimitsResult.isValid());
        assertTrue(invalidLimitsResult.errors().stream().anyMatch(error -> error.contains("maxConnections")));
        assertTrue(invalidLimitsResult.errors().stream().anyMatch(error -> error.contains("tlsReloadIntervalMs")));
    }

    @Test
    void mariaDbOutputAdapterRequiresConnectionConfigParams() {
        OutputAdapterEntity missingParams = OutputAdapterEntity.builder()
                .type("MariaDbOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingResult =
                configValidationService.validateOutputAdapter(missingParams);

        assertFalse(missingResult.isValid());
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("configParams")));

        OutputAdapterEntity valid = OutputAdapterEntity.builder()
                .type("MariaDbOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .configParams("""
                        {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateOutputAdapter(valid).isValid());

        OutputAdapterEntity defaultTableName = OutputAdapterEntity.builder()
                .type("mariadb")
                .messagetype("castrelyx-agent-item")
                .configParams("""
                        {"jdbcUrl":"jdbc:mariadb://mariadb:3306/castrelyx","usernameEnv":"CASTRELYX_DB_USER","passwordEnv":"CASTRELYX_DB_PASSWORD"}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateOutputAdapter(defaultTableName).isValid());
    }

    @Test
    void clickHouseOutputAdapterRequiresEndpointConfigParams() {
        OutputAdapterEntity missingParams = OutputAdapterEntity.builder()
                .type("ClickHouseOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult missingResult =
                configValidationService.validateOutputAdapter(missingParams);

        assertFalse(missingResult.isValid());
        assertTrue(missingResult.errors().stream().anyMatch(error -> error.contains("configParams")));

        OutputAdapterEntity invalidBatch = OutputAdapterEntity.builder()
                .type("ClickHouseOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .configParams("""
                        {"endpointUrl":"http://clickhouse:8123","database":"default","tableName":"castrelyx_agent_events","batchSize":0}
                        """)
                .enabled(true)
                .build();

        ConfigValidationService.ValidationResult invalidBatchResult =
                configValidationService.validateOutputAdapter(invalidBatch);

        assertFalse(invalidBatchResult.isValid());
        assertTrue(invalidBatchResult.errors().stream().anyMatch(error -> error.contains("batchSize")));

        OutputAdapterEntity valid = OutputAdapterEntity.builder()
                .type("ClickHouseOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .configParams("""
                        {"endpointUrl":"http://clickhouse:8123","database":"default","tableName":"castrelyx_agent_events","batchSize":100,"flushIntervalMs":5000,"autoCreateSchema":true}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateOutputAdapter(valid).isValid());

        OutputAdapterEntity invalidSafetyLimits = OutputAdapterEntity.builder()
                .type("ClickHouseOutputAdapter")
                .messagetype("castrelyx-agent-item")
                .configParams("""
                        {"endpointUrl":"http://clickhouse:8123","database":"default","tableName":"castrelyx_agent_events","maxPendingBytes":0,"incompleteChunkDlqDir":" ","maxIncompleteChunkDlqRecords":0}
                        """)
                .enabled(true)
                .build();
        ConfigValidationService.ValidationResult invalidSafetyResult =
                configValidationService.validateOutputAdapter(invalidSafetyLimits);
        assertFalse(invalidSafetyResult.isValid());
        assertTrue(invalidSafetyResult.errors().stream().anyMatch(error -> error.contains("maxPendingBytes")));
        assertTrue(invalidSafetyResult.errors().stream().anyMatch(error -> error.contains("incompleteChunkDlqDir")));
        assertTrue(invalidSafetyResult.errors().stream().anyMatch(error -> error.contains("maxIncompleteChunkDlqRecords")));
    }
}
