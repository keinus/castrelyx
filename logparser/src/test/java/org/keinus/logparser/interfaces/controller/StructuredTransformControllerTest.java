package org.keinus.logparser.interfaces.controller;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.transformation.service.SchemaDefinitionService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StructuredTransformControllerTest {

    @Test
    void saveMappingInvalidatesStructuredTransformCache() {
        SchemaDefinitionService schemaDefinitionService = mock(SchemaDefinitionService.class);
        MappingRepository mappingRepository = mock(MappingRepository.class);
        StructuredTransformService structuredTransformService = mock(StructuredTransformService.class);
        StructuredTransformController controller = new StructuredTransformController(
                schemaDefinitionService,
                mappingRepository,
                structuredTransformService
        );
        MappingConfiguration configuration = new MappingConfiguration();
        configuration.setMessageType("access");

        controller.saveMapping(configuration);

        verify(mappingRepository).save(configuration);
        verify(structuredTransformService).invalidateCache("access");
    }
}
