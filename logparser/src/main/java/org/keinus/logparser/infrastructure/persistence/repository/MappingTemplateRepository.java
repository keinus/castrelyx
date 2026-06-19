package org.keinus.logparser.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import org.keinus.logparser.domain.model.mapping.MappingTemplate;

public interface MappingTemplateRepository {
    List<MappingTemplate> findAll();
    Optional<MappingTemplate> findById(String id);
    Optional<MappingTemplate> findByName(String name);
    MappingTemplate save(MappingTemplate template);
    void deleteById(String id);
}
