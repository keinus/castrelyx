package org.keinus.logparser.interfaces.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keinus.logparser.application.pipeline.OutputAdapterComponent;
import org.keinus.logparser.application.pipeline.PipelineReloadService;
import org.keinus.logparser.application.service.DocumentationService;
import org.keinus.logparser.application.service.LiveTailService;
import org.keinus.logparser.application.service.ThreadMonitoringService;
import org.keinus.logparser.domain.configuration.service.ConfigManagementService;
import org.keinus.logparser.domain.configuration.service.ConfigValidationService;
import org.keinus.logparser.domain.configuration.service.DatabaseConfigLoader;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.parse.service.ParseService;
import org.keinus.logparser.domain.transformation.service.MappingTemplateService;
import org.keinus.logparser.domain.transformation.service.SchemaDefinitionService;
import org.keinus.logparser.domain.transformation.service.StructuredTransformService;
import org.keinus.logparser.infrastructure.persistence.entity.ParserEntity;
import org.keinus.logparser.infrastructure.config.ApplicationProperties;
import org.keinus.logparser.infrastructure.persistence.entity.TransformEntity;
import org.keinus.logparser.infrastructure.persistence.repository.MappingRepository;
import org.keinus.logparser.interfaces.exception.ConfigValidationException;
import org.keinus.logparser.interfaces.exception.GlobalExceptionHandler;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerArgumentBindingTest {

    private ConfigManagementService configService;
    private MappingRepository mappingRepository;
    private MappingTemplateService templateService;
    private DocumentationService documentationService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigManagementService.class);
        mappingRepository = mock(MappingRepository.class);
        templateService = mock(MappingTemplateService.class);
        documentationService = mock(DocumentationService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new ParserController(configService, mock(ConfigValidationService.class), mock(ParseService.class)),
                new TransformController(configService),
                new StructuredTransformController(mock(SchemaDefinitionService.class), mappingRepository,
                        mock(StructuredTransformService.class), templateService),
                new PipelineController(mock(PipelineReloadService.class), mock(OutputAdapterComponent.class),
                        mock(ThreadMonitoringService.class), configService, mock(LiveTailService.class)),
                new DocumentationController(documentationService)
        ).build();

        // Reproduce deployment bytecode without -parameters, even though our Gradle build uses it.
        mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean(RequestMappingHandlerAdapter.class)
                .setParameterNameDiscoverer(mock(ParameterNameDiscoverer.class));
    }

    @Test
    void parserUpdateBindsLongIdWithoutParameterNames() throws Exception {
        ParserEntity saved = ParserEntity.builder().id(17L).type("JsonParser").messagetype("access").build();
        when(configService.updateParser(eq(17L), any(ParserEntity.class))).thenReturn(saved);

        mvc.perform(put("/api/v1/parsers/17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"JsonParser\",\"messagetype\":\"access\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(17));

        verify(configService).updateParser(eq(17L), argThat(entity -> "JsonParser".equals(entity.getType())));
    }

    @Test
    void parserValidationFailureReturnsActionableBadRequest() throws Exception {
        when(configService.updateParser(eq(17L), any(ParserEntity.class)))
                .thenThrow(new ConfigValidationException("Parser validation failed", List.of(
                        "Invalid Java regex in param. Use (?<name>...) instead of Python-style (?P<name>...).")));
        MockMvc validationMvc = MockMvcBuilders.standaloneSetup(
                new ParserController(configService, mock(ConfigValidationService.class), mock(ParseService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        validationMvc.perform(put("/api/v1/parsers/17")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RegexParser\",\"messagetype\":\"syslog\",\"param\":\"(?P<name>.*)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ERR_1001"))
                .andExpect(jsonPath("$.message", containsString("(?<name>...)")));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void parserTestAcceptsTextAndStructuredDataArray(boolean asArray) throws Exception {
        ApplicationProperties properties = mock(ApplicationProperties.class);
        when(properties.getParser()).thenReturn(List.of());
        ParseService parseService = new ParseService(properties, mock(DatabaseConfigLoader.class));
        MockMvc parserMvc = MockMvcBuilders.standaloneSetup(
                new ParserController(configService, mock(ConfigValidationService.class), parseService)).build();
        String structuredData = "[exampleSDID@32473 iut=\"3\" eventSource=\"Application\"]";
        Map<String, Object> request = Map.of(
                "type", "RegexParser",
                "param", "^\\[(?<sdid>\\w+)@(?<id>\\d+)\\s+(?<attributes>.*)\\]$",
                "sampleData", asArray ? List.of(structuredData) : structuredData);

        parserMvc.perform(post("/api/v1/parsers/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sdid").value("exampleSDID"))
                .andExpect(jsonPath("$.id").value("32473"))
                .andExpect(jsonPath("$.attributes").value("iut=\"3\" eventSource=\"Application\""));
    }

    @Test
    void transformUpdateBindsLongIdWithoutParameterNames() throws Exception {
        TransformEntity saved = TransformEntity.builder().id(29L).type("RemoveProperty").messagetype("access").build();
        when(configService.updateTransform(eq(29L), any(TransformEntity.class))).thenReturn(saved);

        mvc.perform(put("/api/v1/transforms/29")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RemoveProperty\",\"messagetype\":\"access\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(29));

        verify(configService).updateTransform(eq(29L), argThat(entity -> "RemoveProperty".equals(entity.getType())));
    }

    @Test
    void priorityUpdatesBindPathAndQueryArguments() throws Exception {
        when(configService.updateParserPriority(17L, 30)).thenReturn(ParserEntity.builder().priority(30).build());
        when(configService.updateTransformPriority(29L, 40)).thenReturn(TransformEntity.builder().priority(40).build());

        mvc.perform(patch("/api/v1/parsers/17/priority").param("priority", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(30));
        mvc.perform(patch("/api/v1/transforms/29/priority").param("priority", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(40));

        verify(configService).updateParserPriority(17L, 30);
        verify(configService).updateTransformPriority(29L, 40);
    }

    @ParameterizedTest
    @ValueSource(strings = { "parsers", "transforms" })
    void missingPriorityRemainsABadRequest(String resource) throws Exception {
        mvc.perform(patch("/api/v1/{resource}/17/priority", resource))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(configService);
    }

    @ParameterizedTest
    @ValueSource(strings = { "parsers", "transforms" })
    void invalidLongIdRemainsABadRequest(String resource) throws Exception {
        mvc.perform(put("/api/v1/{resource}/invalid", resource)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(configService);
    }

    @Test
    void stringPathArgumentsReachConfigurationServices() throws Exception {
        when(configService.getParsersByType("RegexParser")).thenReturn(List.of());
        when(configService.getTransformsByMessageType("Access-Log")).thenReturn(List.of());

        mvc.perform(get("/api/v1/parsers/type/RegexParser")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/transforms/messagetype/Access-Log")).andExpect(status().isOk());

        verify(configService).getParsersByType("RegexParser");
        verify(configService).getTransformsByMessageType("Access-Log");
    }

    @Test
    void structuredMappingAndTemplateApplicationBindNames() throws Exception {
        MappingConfiguration mapping = new MappingConfiguration();
        mapping.setMessageType("Access-Log");
        when(mappingRepository.findByMessageType("Access-Log")).thenReturn(Optional.of(mapping));
        when(templateService.apply("template-1", "Access-Log")).thenReturn(mapping);

        mvc.perform(get("/api/v1/structure/mapping/Access-Log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("Access-Log"));
        mvc.perform(post("/api/v1/structure/templates/template-1/apply").param("messageType", "Access-Log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageType").value("Access-Log"));

        verify(templateService).apply("template-1", "Access-Log");
    }

    @Test
    void processingOrderBindsMessageTypeAndBody() throws Exception {
        mvc.perform(put("/api/v1/pipeline/Access-Log/processing-steps/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"steps\":[{\"kind\":\"parser\",\"id\":17},{\"kind\":\"transform\",\"id\":29}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        verify(configService).reorderProcessingSteps(eq("Access-Log"), argThat(request ->
                request.getSteps().size() == 2 && request.getSteps().get(0).getId().equals(17L)));
    }

    @Test
    void documentationBindsDefaultAndExplicitQueryParameters() throws Exception {
        byte[] text = "# Manual".getBytes(StandardCharsets.UTF_8);
        when(documentationService.readTextDocument("README.md"))
                .thenReturn(new DocumentationService.DocumentAsset("README.md", MediaType.TEXT_PLAIN, text));
        when(documentationService.readRawDocument("readme/manual.md"))
                .thenReturn(new DocumentationService.DocumentAsset("readme/manual.md", MediaType.TEXT_PLAIN, text));

        mvc.perform(get("/api/v1/docs/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("README.md"));
        mvc.perform(get("/api/v1/docs/raw").param("path", "readme/manual.md"))
                .andExpect(status().isOk())
                .andExpect(content().string("# Manual"));

        verify(documentationService).readTextDocument("README.md");
        verify(documentationService).readRawDocument("readme/manual.md");
    }
}
