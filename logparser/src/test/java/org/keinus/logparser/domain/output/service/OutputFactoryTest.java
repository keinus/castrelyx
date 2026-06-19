package org.keinus.logparser.domain.output.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputFactoryTest {

    @Test
    void normalizesLowercaseOutputAdapterAliases() {
        assertEquals("ConsoleOutputAdapter", OutputFactory.normalizeType("console"));
        assertEquals("TcpOutputAdapter", OutputFactory.normalizeType("tcp"));
        assertEquals("HttpOutputAdapter", OutputFactory.normalizeType("http"));
        assertEquals("KafkaOutputAdapter", OutputFactory.normalizeType("kafka"));
        assertEquals("OpenSearchOutputAdapter", OutputFactory.normalizeType("opensearch"));
        assertEquals("RabbitMQAdapter", OutputFactory.normalizeType("rabbitmq"));
        assertEquals("MariaDbOutputAdapter", OutputFactory.normalizeType("mariadb"));
        assertEquals("ClickHouseOutputAdapter", OutputFactory.normalizeType("clickhouse"));
        assertEquals("BenchmarkAdapter", OutputFactory.normalizeType("benchmark"));
    }

    @Test
    void keepsClassNameOutputAdapterTypes() {
        assertEquals("MariaDbOutputAdapter", OutputFactory.normalizeType("MariaDbOutputAdapter"));
    }
}
