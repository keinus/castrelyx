package org.keinus.logparser.infrastructure.persistence.repository;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.infrastructure.persistence.entity.OutputAdapterEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class OutputAdapterRepositoryTest {

    @Autowired
    private OutputAdapterRepository repository;

    @Test
    void testSaveAndFind() {
        OutputAdapterEntity entity = OutputAdapterEntity.builder()
                .type("ConsoleOutputAdapter")
                .messagetype("all")
                .enabled(true)
                .build();

        OutputAdapterEntity saved = repository.save(entity);
        assertNotNull(saved.getId());

        List<OutputAdapterEntity> found = repository.findByMessagetype("all");
        assertFalse(found.isEmpty());
        assertEquals("ConsoleOutputAdapter", found.get(0).getType());
    }

    @Test
    void testFindByType() {
        OutputAdapterEntity entity = OutputAdapterEntity.builder()
                .type("KafkaOutputAdapter")
                .messagetype("logs")
                .enabled(true)
                .build();
        repository.save(entity);

        List<OutputAdapterEntity> results = repository.findByType("KafkaOutputAdapter");
        assertEquals(1, results.size());
    }

    @Test
    void testFindByEnabledTrue() {
        OutputAdapterEntity enabled = OutputAdapterEntity.builder()
                .type("ConsoleOutputAdapter")
                .messagetype("enabled")
                .enabled(true)
                .build();
        OutputAdapterEntity disabled = OutputAdapterEntity.builder()
                .type("ConsoleOutputAdapter")
                .messagetype("disabled")
                .enabled(false)
                .build();

        repository.save(enabled);
        repository.save(disabled);

        List<OutputAdapterEntity> results = repository.findByEnabledTrue();
        assertEquals(1, results.size());
        assertEquals("enabled", results.get(0).getMessagetype());
    }
}
