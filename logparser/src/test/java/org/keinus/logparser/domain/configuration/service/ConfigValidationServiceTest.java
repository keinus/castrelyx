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
                        {"keyStorePath":"/app/certs/logparser-server.p12","keyStorePasswordEnv":"LOGPARSER_KEYSTORE_PASSWORD","trustStorePath":"/app/certs/agent-truststore.p12","trustStorePasswordEnv":"LOGPARSER_TRUSTSTORE_PASSWORD","maxFrameBytes":10485760,"ackMode":"queueAccepted"}
                        """)
                .enabled(true)
                .build();

        assertTrue(configValidationService.validateInputAdapter(valid).isValid());
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
    }
}
