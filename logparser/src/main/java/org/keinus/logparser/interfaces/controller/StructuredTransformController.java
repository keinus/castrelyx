package org.keinus.logparser.interfaces.controller;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.keinus.logparser.domain.model.LogEvent;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.keinus.logparser.domain.model.structured.StructuredEvent;
import org.keinus.logparser.domain.transformation.service.MappingTemplateService;
import org.keinus.logparser.domain.transformation.service.SchemaDefinitionService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.interfaces.dto.transform.SchemaMetadataDto;
import org.keinus.logparser.interfaces.dto.transform.SimulationRequestDto;
import org.keinus.logparser.interfaces.dto.transform.SimulationResponseDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/structure")
public class StructuredTransformController {

    private final SchemaDefinitionService schemaService;
    private final MappingRepository mappingRepository;
    private final StructuredTransformService transformService;
    private final MappingTemplateService templateService;

    public StructuredTransformController(SchemaDefinitionService schemaService,
                               MappingRepository mappingRepository,
                               StructuredTransformService transformService,
                               MappingTemplateService templateService) {
        this.schemaService = schemaService;
        this.mappingRepository = mappingRepository;
        this.transformService = transformService;
        this.templateService = templateService;
    }

    @GetMapping("/schema")
    public ResponseEntity<SchemaMetadataDto> getSchema() {
        return ResponseEntity.ok(schemaService.getSchemaMetadata());
    }

    @GetMapping("/mapping/{messageType}")
    public ResponseEntity<MappingConfiguration> getMapping(@PathVariable String messageType) {
        Optional<MappingConfiguration> config = mappingRepository.findByMessageType(messageType);
        return config.map(ResponseEntity::ok)
                     .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/mapping")
    public ResponseEntity<Void> saveMapping(@RequestBody MappingConfiguration config) {
        mappingRepository.save(config);
        transformService.invalidateCache(config.getMessageType());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/templates")
    public ResponseEntity<List<MappingTemplate>> getTemplates() {
        return ResponseEntity.ok(templateService.findAll());
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<MappingTemplate> getTemplate(@PathVariable String id) {
        try {
            return ResponseEntity.ok(templateService.findById(id));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/templates")
    public ResponseEntity<MappingTemplate> createTemplate(@RequestBody MappingTemplate template) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(template));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<MappingTemplate> updateTemplate(
            @PathVariable String id,
            @RequestBody MappingTemplate template
    ) {
        try {
            return ResponseEntity.ok(templateService.update(id, template));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable String id) {
        try {
            templateService.delete(id);
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @PostMapping("/templates/{id}/apply")
    public ResponseEntity<MappingConfiguration> applyTemplate(
            @PathVariable String id,
            @RequestParam String messageType
    ) {
        try {
            return ResponseEntity.ok(templateService.apply(id, messageType));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/simulate")
    public ResponseEntity<SimulationResponseDto> simulate(@RequestBody SimulationRequestDto request) {
        try {
            // Create dummy LogEvent from sample data
            LogEvent event = new LogEvent();
            event.setMessageType(request.getMessageType());
            if (request.getSampleData() != null) {
                event.setFields(request.getSampleData());
                // Also set original text for preview if 'raw_log' is not in fields?
                // Just dummy text
                event.setOriginalText("Simulated Log Event");
            }

            StructuredEvent result;
            if (request.getTemporaryConfig() != null) {
                result = transformService.transform(event, request.getTemporaryConfig());
            } else {
                result = transformService.transform(event);
            }

            return ResponseEntity.ok(SimulationResponseDto.builder()
                    .result(result)
                    .success(true)
                    .errors(Collections.emptyList())
                    .build());

        } catch (Exception e) {
            log.error("Simulation failed", e);
            return ResponseEntity.ok(SimulationResponseDto.builder()
                    .success(false)
                    .errors(Collections.singletonList(e.getMessage()))
                    .build());
        }
    }
}
