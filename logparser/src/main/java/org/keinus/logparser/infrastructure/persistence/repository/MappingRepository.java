package org.keinus.logparser.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;

public interface MappingRepository {
    Optional<MappingConfiguration> findByMessageType(String messageType);
    List<MappingConfiguration> findAll();
    void save(MappingConfiguration config);
}
