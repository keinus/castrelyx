package org.keinus.logparser.infrastructure.persistence.repository;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.infrastructure.persistence.entity.InputAdapterEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class InputAdapterRepositoryTest {

    @Autowired
    private InputAdapterRepository repository;

    @Test
    void testSaveAndFind() {
        InputAdapterEntity entity = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("syslog")
                .port(514)
                .enabled(true)
                .build();

        InputAdapterEntity saved = repository.save(entity);
        assertNotNull(saved.getId());

        Optional<InputAdapterEntity> found = repository.findByMessagetype("syslog");
        assertTrue(found.isPresent());
        assertEquals("TcpInputAdapter", found.get().getType());
    }

    @Test
    void testFindByType() {
        InputAdapterEntity entity1 = InputAdapterEntity.builder()
                .type("HttpInputAdapter")
                .messagetype("api1")
                .enabled(true)
                .build();
        InputAdapterEntity entity2 = InputAdapterEntity.builder()
                .type("HttpInputAdapter")
                .messagetype("api2")
                .enabled(true)
                .build();
        
        repository.save(entity1);
        repository.save(entity2);

        List<InputAdapterEntity> results = repository.findByType("HttpInputAdapter");
        assertEquals(2, results.size());
    }

    @Test
    void testFindByEnabledTrue() {
        InputAdapterEntity enabled = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("enabled")
                .enabled(true)
                .build();
        InputAdapterEntity disabled = InputAdapterEntity.builder()
                .type("TcpInputAdapter")
                .messagetype("disabled")
                .enabled(false)
                .build();

        repository.save(enabled);
        repository.save(disabled);

        List<InputAdapterEntity> results = repository.findByEnabledTrue();
        assertEquals(1, results.size());
        assertEquals("enabled", results.get(0).getMessagetype());
    }
}
