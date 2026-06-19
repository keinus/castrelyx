package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.infrastructure.persistence.repository.MappingTemplateRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class MappingTemplateService {
    private final MappingTemplateRepository templateRepository;
    private final MappingRepository mappingRepository;
    private final StructuredTransformService transformService;
    private final ObjectMapper objectMapper;

    public MappingTemplateService(
            MappingTemplateRepository templateRepository,
            MappingRepository mappingRepository,
            StructuredTransformService transformService,
            ObjectMapper objectMapper
    ) {
        this.templateRepository = templateRepository;
        this.mappingRepository = mappingRepository;
        this.transformService = transformService;
        this.objectMapper = objectMapper;
    }

    public List<MappingTemplate> findAll() {
        return templateRepository.findAll();
    }

    public MappingTemplate findById(String id) {
        return templateRepository.findById(requireText(id, "id"))
                .orElseThrow(() -> new NoSuchElementException("Mapping template not found: " + id));
    }

    public MappingTemplate create(MappingTemplate request) {
        validateTemplate(request, null);
        Instant now = Instant.now();
        MappingTemplate template = new MappingTemplate();
        template.setId(UUID.randomUUID().toString());
        template.setName(request.getName().trim());
        template.setDescription(blankToNull(request.getDescription()));
        template.setSourceMessageType(blankToNull(request.getSourceMessageType()));
        template.setConfig(copyConfig(request.getConfig()));
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        return templateRepository.save(template);
    }

    public MappingTemplate update(String id, MappingTemplate request) {
        MappingTemplate existing = findById(id);
        validateTemplate(request, existing.getId());
        existing.setName(request.getName().trim());
        existing.setDescription(blankToNull(request.getDescription()));
        existing.setSourceMessageType(blankToNull(request.getSourceMessageType()));
        existing.setConfig(copyConfig(request.getConfig()));
        existing.setUpdatedAt(Instant.now());
        return templateRepository.save(existing);
    }

    public void delete(String id) {
        findById(id);
        templateRepository.deleteById(id);
    }

    public MappingConfiguration apply(String id, String messageType) {
        String targetMessageType = requireText(messageType, "messageType");
        MappingTemplate template = findById(id);
        MappingConfiguration config = copyConfig(template.getConfig());
        config.setMessageType(targetMessageType);
        if (config.getId() == null || config.getId().isBlank()) {
            config.setId(template.getId());
        }
        mappingRepository.save(config);
        transformService.invalidateCache(targetMessageType);
        return config;
    }

    private void validateTemplate(MappingTemplate request, String currentId) {
        if (request == null) {
            throw new IllegalArgumentException("Mapping template request is required");
        }
        String name = requireText(request.getName(), "name");
        templateRepository.findByName(name)
                .filter(existing -> currentId == null || !currentId.equals(existing.getId()))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("Mapping template name already exists: " + name);
                });
        if (request.getConfig() == null) {
            throw new IllegalArgumentException("config is required");
        }
    }

    private MappingConfiguration copyConfig(MappingConfiguration config) {
        return objectMapper.convertValue(config, MappingConfiguration.class);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
