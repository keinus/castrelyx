package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.mapping.FieldMapping;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.infrastructure.persistence.repository.MappingTemplateRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MappingTemplateServiceTest {

    @Test
    void applyCopiesTemplateConfigToTargetMessageTypeAndInvalidatesCache() {
        MappingTemplateRepository templateRepository = mock(MappingTemplateRepository.class);
        MappingRepository mappingRepository = mock(MappingRepository.class);
        StructuredTransformService transformService = mock(StructuredTransformService.class);
        MappingTemplateService service = new MappingTemplateService(
                templateRepository,
                mappingRepository,
                transformService,
                new ObjectMapper()
        );
        MappingConfiguration config = new MappingConfiguration();
        config.setId("source-config");
        config.setMessageType("source");
        config.setCommonMappings(List.of(new FieldMapping("src", "dst", null)));
        MappingTemplate template = new MappingTemplate();
        template.setId("template-1");
        template.setName("Template");
        template.setConfig(config);
        when(templateRepository.findById("template-1")).thenReturn(Optional.of(template));

        MappingConfiguration applied = service.apply("template-1", "target");

        assertThat(applied.getMessageType()).isEqualTo("target");
        assertThat(applied.getCommonMappings()).hasSize(1);
        assertThat(template.getConfig().getMessageType()).isEqualTo("source");
        verify(mappingRepository).save(applied);
        verify(transformService).invalidateCache("target");
    }

    @Test
    void createRejectsDuplicateTemplateName() {
        MappingTemplateRepository templateRepository = mock(MappingTemplateRepository.class);
        MappingTemplateService service = new MappingTemplateService(
                templateRepository,
                mock(MappingRepository.class),
                mock(StructuredTransformService.class),
                new ObjectMapper()
        );
        MappingTemplate existing = new MappingTemplate();
        existing.setId("existing");
        existing.setName("Access");
        when(templateRepository.findByName("Access")).thenReturn(Optional.of(existing));
        MappingTemplate request = new MappingTemplate();
        request.setName("Access");
        request.setConfig(new MappingConfiguration());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createPersistsGeneratedIdAndTimestamps() {
        MappingTemplateRepository templateRepository = mock(MappingTemplateRepository.class);
        when(templateRepository.save(any(MappingTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MappingTemplateService service = new MappingTemplateService(
                templateRepository,
                mock(MappingRepository.class),
                mock(StructuredTransformService.class),
                new ObjectMapper()
        );
        MappingTemplate request = new MappingTemplate();
        request.setName(" Access ");
        request.setDescription(" reusable ");
        request.setSourceMessageType(" access ");
        request.setConfig(new MappingConfiguration());

        MappingTemplate saved = service.create(request);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getName()).isEqualTo("Access");
        assertThat(saved.getDescription()).isEqualTo("reusable");
        assertThat(saved.getSourceMessageType()).isEqualTo("access");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }
}
