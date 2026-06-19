package org.keinus.logparser.domain.configuration.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigMetadataServiceTest {

    private final ConfigMetadataService service = new ConfigMetadataService();

    @Test
    void testGetInputAdapterTypes() {
        List<ConfigMetadataService.AdapterTypeInfo> types = service.getInputAdapterTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.type().equals("TcpInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("TlsTcpInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("HttpsInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("SnmpInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("RabbitMqInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("TlsRabbitMqInputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("TcpMtlsGzipInputAdapter")));
    }

    @Test
    void testGetOutputAdapterTypes() {
        List<ConfigMetadataService.AdapterTypeInfo> types = service.getOutputAdapterTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.type().equals("HttpOutputAdapter")));
        assertTrue(types.stream().anyMatch(t -> t.type().equals("MariaDbOutputAdapter")));
    }

    @Test
    void testGetParserTypes() {
        List<ConfigMetadataService.AdapterTypeInfo> types = service.getParserTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.type().equals("GrokParser")));
    }

    @Test
    void testGetTransformTypes() {
        List<ConfigMetadataService.TransformTypeInfo> types = service.getTransformTypes();
        assertFalse(types.isEmpty());
        assertTrue(types.stream().anyMatch(t -> t.type().equals("Filter")));
    }

    @Test
    void testGetInputAdapterSchema() {
        ConfigMetadataService.AdapterSchema schema = service.getInputAdapterSchema("TcpInputAdapter");
        assertEquals("TcpInputAdapter", schema.type());
        assertFalse(schema.fields().isEmpty());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("port")));

        schema = service.getInputAdapterSchema("TlsTcpInputAdapter");
        assertEquals("TlsTcpInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("port") && f.required()));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("HttpsInputAdapter");
        assertEquals("HttpsInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("port") && f.required()));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("SnmpInputAdapter");
        assertEquals("SnmpInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("RabbitMqInputAdapter");
        assertEquals("RabbitMqInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("TlsRabbitMqInputAdapter");
        assertEquals("TlsRabbitMqInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("TcpMtlsGzipInputAdapter");
        assertEquals("TcpMtlsGzipInputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("port") && f.required()));
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getInputAdapterSchema("Unknown");
        assertTrue(schema.fields().isEmpty());
    }

    @Test
    void testGetOutputAdapterSchema() {
        ConfigMetadataService.AdapterSchema schema = service.getOutputAdapterSchema("HttpOutputAdapter");
        assertEquals("HttpOutputAdapter", schema.type());
        assertFalse(schema.fields().isEmpty());

        schema = service.getOutputAdapterSchema("MariaDbOutputAdapter");
        assertEquals("MariaDbOutputAdapter", schema.type());
        assertTrue(schema.fields().stream().anyMatch(f -> f.name().equals("configParams") && f.required()));

        schema = service.getOutputAdapterSchema("Unknown");
        assertTrue(schema.fields().isEmpty());
    }

    @Test
    void testGetParserSchema() {
        ConfigMetadataService.AdapterSchema schema = service.getParserSchema("GrokParser");
        assertEquals("GrokParser", schema.type());
        assertFalse(schema.fields().isEmpty());
    }

    @Test
    void testGetTransformSchema() {
        ConfigMetadataService.TransformSchema schema = service.getTransformSchema("Filter");
        assertEquals("Filter", schema.type());
        assertFalse(schema.fields().isEmpty());
    }

    @Test
    void testSupportedOptions() {
        assertFalse(service.getSupportedCodecs().isEmpty());
        assertFalse(service.getSupportedHttpMethods().isEmpty());
    }
}
