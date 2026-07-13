package org.keinus.logparser.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MappingTemplateRepositoryTest {

    private SqliteMappingTemplateRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:mapping_template_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        DataSource ds = dataSource;

        repository = new SqliteMappingTemplateRepository(ds, new ObjectMapper());
        repository.initTable();
    }

    @Test
    void shouldSaveAndFindById() {
        MappingTemplate template = template("template-1", "Access");

        repository.save(template);

        assertThat(repository.findById("template-1")).isPresent();
        MappingTemplate found = repository.findById("template-1").orElseThrow();
        assertThat(found.getName()).isEqualTo("Access");
        assertThat(found.getConfig().getCommonMappings())
                .extracting(FieldMapping::getTargetField)
                .containsExactly("event_type");
    }

    @Test
    void shouldFindByNameCaseInsensitive() {
        repository.save(template("template-1", "Access Logs"));

        assertThat(repository.findByName("access logs")).isPresent();
    }

    @Test
    void shouldUpdateAndFindAllTemplates() {
        MappingTemplate first = template("template-1", "Beta");
        MappingTemplate second = template("template-2", "Alpha");
        repository.save(first);
        repository.save(second);

        first.setName("Gamma");
        first.setUpdatedAt(Instant.now());
        repository.save(first);

        assertThat(repository.findAll())
                .extracting(MappingTemplate::getName)
                .containsExactly("Alpha", "Gamma");
    }

    @Test
    void shouldDeleteById() {
        repository.save(template("template-1", "Access"));

        repository.deleteById("template-1");

        assertThat(repository.findById("template-1")).isEmpty();
    }

    @Test
    void shouldReuseTransactionBoundConnectionWithSingleConnectionPool() {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setDriverClassName("org.h2.Driver");
            pool.setJdbcUrl("jdbc:h2:mem:template_tx_" + UUID.randomUUID().toString().replace("-", "")
                    + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
            pool.setUsername("sa");
            pool.setPassword("");
            pool.setMaximumPoolSize(1);
            pool.setConnectionTimeout(250);

            SqliteMappingTemplateRepository transactionalRepository =
                    new SqliteMappingTemplateRepository(pool, new ObjectMapper());
            transactionalRepository.initTable();
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(pool));

            transaction.executeWithoutResult(status -> {
                transactionalRepository.save(template("transactional", "Transactional"));
                assertThat(transactionalRepository.findById("transactional")).isPresent();
            });
        }
    }

    private MappingTemplate template(String id, String name) {
        MappingConfiguration config = new MappingConfiguration();
        config.setMessageType("source");
        config.setCommonMappings(List.of(new FieldMapping("type", "event_type", null)));

        MappingTemplate template = new MappingTemplate();
        template.setId(id);
        template.setName(name);
        template.setDescription("description");
        template.setSourceMessageType("source");
        template.setConfig(config);
        template.setCreatedAt(Instant.now());
        template.setUpdatedAt(Instant.now());
        return template;
    }
}
