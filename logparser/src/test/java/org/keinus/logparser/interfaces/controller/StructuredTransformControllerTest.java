package org.keinus.logparser.interfaces.controller;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.keinus.logparser.domain.transformation.service.MappingTemplateService;
import org.keinus.logparser.domain.transformation.service.SchemaDefinitionService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredTransformControllerTest {

    @Test
    void saveMappingInvalidatesStructuredTransformCache() {
        SchemaDefinitionService schemaDefinitionService = mock(SchemaDefinitionService.class);
        MappingRepository mappingRepository = mock(MappingRepository.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        MappingTemplateService mappingTemplateService = mock(MappingTemplateService.class);
        StructuredTransformController controller = new StructuredTransformController(
                schemaDefinitionService,
                mappingRepository,
                structuredTransformService,
                mappingTemplateService
        );
        MappingConfiguration configuration = new MappingConfiguration();
        configuration.setMessageType("access");

        controller.saveMapping(configuration);

        verify(mappingRepository).save(configuration);
        verify(structuredTransformService).invalidateCache("access");
    }

    @Test
    void applyTemplateDelegatesToTemplateService() {
        SchemaDefinitionService schemaDefinitionService = mock(SchemaDefinitionService.class);
        MappingRepository mappingRepository = mock(MappingRepository.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        MappingTemplateService mappingTemplateService = mock(MappingTemplateService.class);
        StructuredTransformController controller = new StructuredTransformController(
                schemaDefinitionService,
                mappingRepository,
                structuredTransformService,
                mappingTemplateService
        );
        MappingConfiguration applied = new MappingConfiguration();
        applied.setMessageType("access");
        when(mappingTemplateService.apply("template-1", "access")).thenReturn(applied);

        ResponseEntity<MappingConfiguration> response = controller.applyTemplate("template-1", "access");

        assertThat(response.getBody()).isSameAs(applied);
        verify(mappingTemplateService).apply("template-1", "access");
    }

    @Test
    void createTemplateReturnsCreatedTemplate() {
        SchemaDefinitionService schemaDefinitionService = mock(SchemaDefinitionService.class);
        MappingRepository mappingRepository = mock(MappingRepository.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        MappingTemplateService mappingTemplateService = mock(MappingTemplateService.class);
        StructuredTransformController controller = new StructuredTransformController(
                schemaDefinitionService,
                mappingRepository,
                structuredTransformService,
                mappingTemplateService
        );
        MappingTemplate request = new MappingTemplate();
        request.setName("Access");
        MappingTemplate created = new MappingTemplate();
        created.setId("template-1");
        created.setName("Access");
        when(mappingTemplateService.create(request)).thenReturn(created);

        ResponseEntity<MappingTemplate> response = controller.createTemplate(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isSameAs(created);
        verify(mappingTemplateService).create(request);
    }
}
