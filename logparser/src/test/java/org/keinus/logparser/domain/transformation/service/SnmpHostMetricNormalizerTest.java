package org.keinus.logparser.domain.transformation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnmpHostMetricNormalizerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final String U = "1.3.6.1.4.1.2021.";

    @Test void convertsUnitsAndUsesMemAvailableInsteadOfMemFree() {
        ObjectNode poll = poll();
        oid(poll, U + "4.5.0", 1000); oid(poll, U + "4.6.0", 10);
        oid(poll, U + "4.27.0", "300"); oid(poll, U + "11.67.0", 4);
        oid(poll, U + "10.1.5.1", 250);
        oid(poll, U + "13.16.2.1.2.1", "Package id 0");
        oid(poll, U + "13.16.2.1.3.1", 42500);
        var result = new SnmpHostMetricNormalizer().normalize("server", NOW, poll);
        assertEquals(1024000, metric(result, "host.memory.total_bytes").value());
        assertEquals(307200, metric(result, "host.memory.available_bytes").value());
        assertEquals(70, metric(result, "memory.usage").value());
        assertEquals(2.5, metric(result, "host.load.1").value());
        assertEquals(62.5, metric(result, "host.load.normalized_1").value());
        assertEquals(42.5, metric(result, "host.temperature.celsius").value());
        assertEquals("celsius", metric(result, "host.temperature.celsius").unit());
    }

    @Test void doesNotInventAvailabilityOrZeroForMissingAndInvalidValues() {
        var poll = poll(); oid(poll, U + "4.5.0", 1000); oid(poll, U + "4.6.0", 10);
        oid(poll, U + "4.27.0", 0).put("status", "noSuchInstance");
        oid(poll, U + "10.1.5.1", "NaN");
        var result = new SnmpHostMetricNormalizer().normalize("server", NOW, poll);
        assertTrue(result.metrics().stream().noneMatch(m -> List.of("memory.usage", "host.memory.available_bytes", "host.load.1", "cpu.usage").contains(m.name())));
        assertTrue(result.handledNames().contains("alias-" + U + "4.27.0"));
    }

    @Test void prefersCounter64AndAttachesAgentCompatibleInterfaceLabels() {
        var poll = poll();
        oid(poll, "1.3.6.1.2.1.31.1.1.1.1.21", "bond0");
        oid(poll, "1.3.6.1.2.1.31.1.1.1.6.21", "5000000000");
        oid(poll, "1.3.6.1.2.1.2.2.1.10.21", 705032704);
        oid(poll, "1.3.6.1.2.1.31.1.1.1.10.21", "6000000000");
        var result = new SnmpHostMetricNormalizer().normalize("server", NOW, poll);
        assertEquals(1, result.metrics().stream().filter(m -> m.name().equals("host.network.rx_bytes")).count());
        var rx = metric(result, "host.network.rx_bytes");
        assertEquals(5000000000d, rx.value());
        assertEquals("bytes", rx.unit()); assertEquals("bond0", rx.labels().get("interface"));
        assertEquals("ingress", rx.labels().get("direction"));
        assertTrue(result.handledNames().contains("alias-1.3.6.1.2.1.2.2.1.10.21"));
    }

    @Test void handlesLargeDisksReservedBlocksAndDiskIoMicroseconds() {
        var poll = poll();
        oid(poll, U + "9.1.2.1", "/data"); oid(poll, U + "9.1.3.1", "/dev/sda1");
        oid(poll, U + "9.1.11.1", "205032704"); oid(poll, U + "9.1.12.1", 1);
        oid(poll, U + "9.1.13.1", "3000000000"); oid(poll, U + "9.1.14.1", 0);
        oid(poll, U + "9.1.15.1", "1000000001"); oid(poll, U + "9.1.16.1", 0);
        oid(poll, U + "13.15.1.1.2.9", "sda");
        oid(poll, U + "13.15.1.1.12.9", "9876543210");
        oid(poll, U + "13.15.1.1.14.9", "12345000");
        var result = new SnmpHostMetricNormalizer().normalize("server", NOW, poll);
        assertEquals(4500000000d * 1024, metric(result, "host.disk.total_bytes").value());
        assertEquals(3000000000d * 1024, metric(result, "host.disk.available_bytes").value());
        assertEquals(26, metric(result, "host.disk.used_percent").value());
        assertEquals(26, metric(result, "disk.usage").value());
        assertEquals("/dev/sda1", metric(result, "host.disk.used_percent").labels().get("filesystem"));
        assertEquals(12345, metric(result, "host.disk.io_time_ms").value());
        assertEquals("milliseconds", metric(result, "host.disk.io_time_ms").unit());
        assertEquals("sda", metric(result, "host.disk.read_bytes").labels().get("device"));
    }

    @Test void hostResourcesFreeSpaceIsNotMislabeledAsNonRootAvailableSpace() {
        var poll = poll(); String base = "1.3.6.1.2.1.25.2.3.1.";
        oid(poll, base + "3.32", "/"); oid(poll, base + "4.32", 4096);
        oid(poll, base + "5.32", 100); oid(poll, base + "6.32", 25);
        var result = new SnmpHostMetricNormalizer().normalize("server", NOW, poll);
        assertEquals(307200, metric(result, "host.disk.free_bytes").value());
        assertTrue(result.metrics().stream().noneMatch(m -> m.name().equals("host.disk.available_bytes")));
    }

    @Test void cpuMatchesAgentIdleDefinitionAndHandlesRetriesResetsAndSeparateHosts() {
        var normalizer = new SnmpHostMetricNormalizer();
        var first = cpu(100, 100, 100, 100, 100, 100, 100, 100);
        var next = cpu(120, 100, 110, 160, 105, 101, 104, 100);
        assertTrue(normalizer.normalize("server", NOW, first).metrics().isEmpty());
        var result = normalizer.normalize("server", NOW.plusSeconds(30), next);
        assertEquals(35, metric(result, "cpu.usage").value());
        assertEquals(35, metric(normalizer.normalize("server", NOW.plusSeconds(30), next), "cpu.usage").value());
        assertTrue(normalizer.normalize("other", NOW.plusSeconds(30), next).metrics().isEmpty());
        assertTrue(normalizer.normalize("server", NOW.minusSeconds(30), first).metrics().isEmpty());
        assertTrue(normalizer.normalize("server", NOW.plusSeconds(60), first).metrics().isEmpty());
        assertEquals(35, metric(normalizer.normalize("server", NOW.plusSeconds(90), next), "cpu.usage").value());
    }

    @Test void cpuRebootInvalidatesEvenIfCountersHaveAlreadyExceededPreviousSample() {
        var normalizer = new SnmpHostMetricNormalizer();
        var first = cpu(100, 100, 100, 100, 100, 100, 100, 100);
        var next = cpu(120, 100, 110, 160, 105, 101, 104, 100);
        oid(first, "1.3.6.1.2.1.25.1.1.0", 10000);
        oid(next, "1.3.6.1.2.1.25.1.1.0", 100);
        normalizer.normalize("server", NOW, first);
        assertTrue(normalizer.normalize("server", NOW.plusSeconds(30), next).metrics().stream().noneMatch(m -> m.name().equals("cpu.usage")));
    }

    private static ObjectNode cpu(long... values) {
        var poll = poll(); int[] columns = {50, 51, 52, 53, 54, 56, 61, 64};
        for (int i = 0; i < columns.length; i++) oid(poll, U + "11." + columns[i] + ".0", values[i]);
        return poll;
    }
    private static ObjectNode poll() { var p = JSON.createObjectNode(); p.putObject("metrics"); return p; }
    private static ObjectNode oid(ObjectNode p, String oid, Object value) {
        var node = p.withObject("/metrics").putObject("alias-" + oid).put("oid", "." + oid).put("status", "success");
        node.set("value", JSON.valueToTree(value)); return node;
    }
    private static SnmpHostMetricNormalizer.Metric metric(SnmpHostMetricNormalizer.Result result, String name) {
        return result.metrics().stream().filter(m -> name.equals(m.name())).findFirst().orElseThrow();
    }
}
