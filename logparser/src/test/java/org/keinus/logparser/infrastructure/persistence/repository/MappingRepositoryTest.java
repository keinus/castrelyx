package org.keinus.logparser.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MappingRepositoryTest {

    private SqliteMappingRepository mappingRepository;
    private DataSource dataSource;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Use H2 in-memory database for testing
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUsername("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.objectMapper = new ObjectMapper();
        
        mappingRepository = new SqliteMappingRepository(dataSource, objectMapper);
        mappingRepository.initTable();
    }

    @Test
    void shouldSaveAndFindByMessageType() {
        MappingConfiguration config = new MappingConfiguration();
        config.setId("id1");
        config.setMessageType("test-type");

        mappingRepository.save(config);

        Optional<MappingConfiguration> result = mappingRepository.findByMessageType("test-type");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("id1");
        assertThat(result.get().getMessageType()).isEqualTo("test-type");
    }

    @Test
    void shouldReturnEmptyWhenNotFound() {
        Optional<MappingConfiguration> result = mappingRepository.findByMessageType("non-existent");
        assertThat(result).isEmpty();
    }

    @Test
    void shouldOverwriteWhenSavingSameMessageType() {
        MappingConfiguration config1 = new MappingConfiguration();
        config1.setId("id1");
        config1.setMessageType("test-type");
        mappingRepository.save(config1);

        MappingConfiguration config2 = new MappingConfiguration();
        config2.setId("id2");
        config2.setMessageType("test-type");
        mappingRepository.save(config2);

        Optional<MappingConfiguration> result = mappingRepository.findByMessageType("test-type");
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("id2");
    }

    @Test
    void shouldFindAllMappings() {
        MappingConfiguration config1 = new MappingConfiguration();
        config1.setMessageType("type-a");
        mappingRepository.save(config1);

        MappingConfiguration config2 = new MappingConfiguration();
        config2.setMessageType("type-b");
        mappingRepository.save(config2);

        assertThat(mappingRepository.findAll())
                .extracting(MappingConfiguration::getMessageType)
                .contains("type-a", "type-b");
    }

    @Test
    void shouldReuseTransactionBoundConnectionWithSingleConnectionPool() {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setDriverClassName("org.h2.Driver");
            pool.setJdbcUrl("jdbc:h2:mem:tx_" + UUID.randomUUID().toString().replace("-", "")
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(250);

            SqliteMappingRepository transactionalRepository = new SqliteMappingRepository(pool, new ObjectMapper());
            transactionalRepository.initTable();
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));

            transaction.executeWithoutResult(status -> {
                MappingConfiguration config = new MappingConfiguration();
                config.setId("transactional");
                config.setMessageType("castrelyx-agent-item");
                transactionalRepository.save(config);
                assertThat(transactionalRepository.findByMessageType("castrelyx-agent-item"))
                        .map(MappingConfiguration::getId)
                        .contains("transactional");
            });
        }
    }
}
