package org.keinus.logparser.domain.output.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts supported MIB objects to the host metric contract used by Castrelyx agents. */
public final class SnmpHostMetricNormalizer {
    private static final String UCD = "1.3.6.1.4.1.2021.";
    private static final String IF = "1.3.6.1.2.1.2.2.1.";
    private static final String IFX = "1.3.6.1.2.1.31.1.1.1.";
    private static final String STORAGE = "1.3.6.1.2.1.25.2.3.1.";
    private static final int[] CPU_COLUMNS = {50, 51, 52, 53, 54, 56, 61, 64};
    private final LinkedHashMap<String, CpuSample> cpuSamples = new LinkedHashMap<>();

    public record Metric(String name, double value, String unit, Map<String, String> labels) {}
    public record Result(List<Metric> metrics, Set<String> handledNames) {}
    private record CpuSample(Instant at, double[] counters, Double uptime, Double usage) {}

    public synchronized Result normalize(String source, Instant at, JsonNode payload) {
        var poll = new Poll(payload.path("metrics"));
        List<Metric> out = new ArrayList<>();
        memory(poll, out);
        Double cpuCount = poll.number(UCD + "11.67.0");
        if (cpuCount != null && cpuCount > 0) add(out, "host.cpu.count", cpuCount, "count", Map.of());
        for (int index = 1; index <= 3; index++) {
            Double load = poll.number(UCD + "10.1.5." + index);
            if (load != null) {
                add(out, "host.load." + new int[]{1, 5, 15}[index - 1], load / 100, "count", Map.of());
                if (index == 1 && cpuCount != null && cpuCount > 0)
                    add(out, "host.load.normalized_1", load / cpuCount, "percent", Map.of());
            }
        }
        // Raw Net-SNMP CPU counters use Linux /proc/stat fields. As in the Go agent,
        // idle includes iowait and guest time is not added a second time.
        Double usage = cpuUsage(source, at, poll);
        if (usage != null) {
            add(out, "cpu.usage", usage, "percent", Map.of());
            add(out, "host.cpu.usage_percent", usage, "percent", Map.of());
        }
        interfaces(poll, out);
        filesystems(poll, out);
        diskIo(poll, out);
        for (String index : poll.indices(UCD + "13.16.2.1.3.")) {
            Double value = poll.number(UCD + "13.16.2.1.3." + index);
            if (value != null) add(out, "host.temperature.celsius", value / 1000, "celsius",
                    Map.of("sensor", poll.text(UCD + "13.16.2.1.2." + index, index), "sensor_index", index));
        }
        Double uptime = poll.number("1.3.6.1.2.1.25.1.1.0");
        if (uptime != null) add(out, "host.uptime_seconds", uptime / 100, "seconds", Map.of());
        return new Result(List.copyOf(out), Set.copyOf(poll.handled));
    }

    private static void memory(Poll p, List<Metric> out) {
        Double total = p.number(UCD + "4.20.0", UCD + "4.5.0");
        // memAvailReal is free memory, NOT Linux MemAvailable. Do not substitute it.
        Double available = p.number(UCD + "4.27.0");
        scaled(out, "host.memory.total_bytes", total, 1024, "bytes", Map.of());
        scaled(out, "host.memory.available_bytes", available, 1024, "bytes", Map.of());
        if (total != null && available != null && total > 0 && available <= total)
            add(out, "memory.usage", (total - available) * 100 / total, "percent", Map.of());
        scaled(out, "host.memory.free_bytes", p.number(UCD + "4.21.0", UCD + "4.6.0"), 1024, "bytes", Map.of());
        scaled(out, "host.memory.buffer_bytes", p.number(UCD + "4.25.0", UCD + "4.14.0"), 1024, "bytes", Map.of());
        scaled(out, "host.memory.cached_bytes", p.number(UCD + "4.26.0", UCD + "4.15.0"), 1024, "bytes", Map.of());
        scaled(out, "host.swap.total_bytes", p.number(UCD + "4.18.0", UCD + "4.3.0"), 1024, "bytes", Map.of());
        scaled(out, "host.swap.available_bytes", p.number(UCD + "4.19.0", UCD + "4.4.0"), 1024, "bytes", Map.of());
    }

    private Double cpuUsage(String source, Instant at, Poll p) {
        double[] counters = new double[CPU_COLUMNS.length];
        boolean complete = true;
        for (int i = 0; i < counters.length; i++) {
            Double value = p.number(UCD + "11." + CPU_COLUMNS[i] + ".0");
            if (value == null) complete = false;
            else counters[i] = value;
        }
        if (!complete || Arrays.stream(counters).sum() == 0) return null;
        Double uptime = p.number("1.3.6.1.2.1.25.1.1.0");
        CpuSample previous = cpuSamples.get(source);
        if (previous != null && !at.isAfter(previous.at())) {
            return at.equals(previous.at()) && Arrays.equals(counters, previous.counters()) ? previous.usage() : null;
        }
        Double usage = null;
        if (previous != null && Duration.between(previous.at(), at).toSeconds() <= 300
                && (uptime == null || previous.uptime() == null || uptime >= previous.uptime())) {
            double total = 0, idle = 0;
            boolean valid = true;
            for (int i = 0; i < counters.length; i++) {
                double delta = counters[i] - previous.counters()[i];
                if (delta < 0) valid = false; // reboot or Counter32 wrap: warm up again, never emit a spike
                total += delta;
                if (i == 3 || i == 4) idle += delta;
            }
            if (valid && total > 0 && idle <= total) usage = (total - idle) * 100 / total;
        }
        cpuSamples.remove(source);
        cpuSamples.put(source, new CpuSample(at, counters, uptime, usage));
        while (cpuSamples.size() > 4096) cpuSamples.remove(cpuSamples.keySet().iterator().next());
        return usage;
    }

    private static void interfaces(Poll p, List<Metric> out) {
        Set<String> indices = new HashSet<>(p.indices(IFX + "6."));
        indices.addAll(p.indices(IFX + "10."));
        indices.addAll(p.indices(IF + "10."));
        indices.addAll(p.indices(IF + "16."));
        for (String index : indices) {
            String name = p.text(IFX + "1." + index, p.text(IF + "2." + index, "ifIndex:" + index));
            scaled(out, "host.network.rx_bytes", p.number(IFX + "6." + index, IF + "10." + index), 1, "bytes",
                    Map.of("interface", name, "direction", "ingress"));
            scaled(out, "host.network.tx_bytes", p.number(IFX + "10." + index, IF + "16." + index), 1, "bytes",
                    Map.of("interface", name, "direction", "egress"));
            for (var entry : Map.of(13, "in.discards", 14, "in.errors", 19, "out.discards", 20, "out.errors").entrySet())
                scaled(out, "interface." + entry.getValue(), p.number(IF + entry.getKey() + "." + index), 1, "count", Map.of("interface", name));
            scaled(out, "interface.admin_status", p.number(IF + "7." + index), 1, "state", Map.of("interface", name));
            scaled(out, "interface.oper_status", p.number(IF + "8." + index), 1, "state", Map.of("interface", name));
            scaled(out, "interface.speed_mbps", p.number(IFX + "15." + index), 1, "Mbps", Map.of("interface", name));
            p.number(IFX + "19." + index); // discontinuity metadata remains in the raw poll
        }
    }

    private static void filesystems(Poll p, List<Metric> out) {
        Set<String> mounts = new HashSet<>();
        Double maxUsage = null;
        for (String index : p.indices(UCD + "9.1.2.")) {
            String mount = p.text(UCD + "9.1.2." + index, "");
            String device = p.text(UCD + "9.1.3." + index, "");
            Double total = p.diskKilobytes(index, 11, 12, 6);
            Double available = p.diskKilobytes(index, 13, 14, 7);
            Double used = p.diskKilobytes(index, 15, 16, 8);
            if (!mount.startsWith("/") || total == null || total <= 0 || available == null || used == null
                    || used + available <= 0 || used > total || available > total) continue;
            Map<String, String> labels = Map.of("mount_point", mount, "filesystem", device);
            scaled(out, "host.disk.total_bytes", total, 1024, "bytes", labels);
            scaled(out, "host.disk.used_bytes", used, 1024, "bytes", labels);
            scaled(out, "host.disk.available_bytes", available, 1024, "bytes", labels);
            // GNU df (the Linux agent's source) excludes reserved blocks and rounds upward.
            double usage = Math.ceil(used * 100 / (used + available));
            add(out, "host.disk.used_percent", usage, "percent", labels);
            maxUsage = maxUsage == null ? usage : Math.max(maxUsage, usage);
            mounts.add(mount);
        }
        if (maxUsage != null) add(out, "disk.usage", maxUsage, "percent", Map.of());
        for (String index : p.indices(STORAGE + "5.")) {
            String mount = p.text(STORAGE + "3." + index, "");
            Double allocation = p.number(STORAGE + "4." + index);
            Double size = p.number(STORAGE + "5." + index);
            Double used = p.number(STORAGE + "6." + index);
            if (!mount.startsWith("/") || mounts.contains(mount) || allocation == null || allocation <= 0
                    || size == null || size <= 0 || used == null || used > size) continue;
            var labels = Map.of("mount_point", mount);
            scaled(out, "host.disk.total_bytes", size, allocation, "bytes", labels);
            scaled(out, "host.disk.used_bytes", used, allocation, "bytes", labels);
            // HOST-RESOURCES does not describe non-root available space/reserved blocks.
            scaled(out, "host.disk.free_bytes", size - used, allocation, "bytes", labels);
        }
    }

    private static void diskIo(Poll p, List<Metric> out) {
        String base = UCD + "13.15.1.1.";
        for (String index : p.indices(base + "2.")) {
            var labels = Map.of("device", p.text(base + "2." + index, "diskIndex:" + index));
            scaled(out, "host.disk.read_bytes", p.number(base + "12." + index, base + "3." + index), 1, "bytes", labels);
            scaled(out, "host.disk.write_bytes", p.number(base + "13." + index, base + "4." + index), 1, "bytes", labels);
            scaled(out, "host.disk.read_ops", p.number(base + "5." + index), 1, "count", labels);
            scaled(out, "host.disk.write_ops", p.number(base + "6." + index), 1, "count", labels);
            scaled(out, "host.disk.io_time_ms", p.number(base + "14." + index), 0.001, "milliseconds", labels);
        }
    }

    private static void scaled(List<Metric> out, String name, Double value, double factor, String unit, Map<String, String> labels) {
        if (value != null) add(out, name, value * factor, unit, labels);
    }

    private static void add(List<Metric> out, String name, double value, String unit, Map<String, String> labels) {
        if (Double.isFinite(value)) out.add(new Metric(name, value, unit, labels));
    }

    private static final class Poll {
        final Map<String, JsonNode> values = new LinkedHashMap<>();
        final Map<String, List<String>> names = new LinkedHashMap<>();
        final Set<String> handled = new HashSet<>();

        Poll(JsonNode metrics) {
            if (metrics.isObject()) metrics.fields().forEachRemaining(e -> put(e.getKey(), e.getValue()));
            else if (metrics.isArray()) metrics.forEach(v -> put(v.path("name").asText(), v));
        }

        private void put(String name, JsonNode value) {
            String oid = value.path("oid").asText().replaceFirst("^\\.", "");
            if (!oid.isEmpty()) {
                values.putIfAbsent(oid, value);
                names.computeIfAbsent(oid, ignored -> new ArrayList<>()).add(name);
            }
        }

        JsonNode get(String oid) {
            handled.addAll(names.getOrDefault(oid, List.of()));
            JsonNode node = values.get(oid);
            return node != null && "success".equals(node.path("status").asText("success")) ? node.path("value") : null;
        }

        Double number(String... oids) {
            Double result = null;
            for (String oid : oids) {
                JsonNode value = get(oid);
                if (value == null || value.isNull() || value.isMissingNode() || value.isBoolean()) continue;
                try {
                    double parsed = Double.parseDouble(value.asText());
                    if (result == null && Double.isFinite(parsed) && parsed >= 0) result = parsed;
                } catch (NumberFormatException ignored) { }
            }
            return result;
        }

        String text(String oid, String fallback) {
            JsonNode value = get(oid);
            return value == null || value.isMissingNode() || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
        }

        Set<String> indices(String prefix) {
            Set<String> indices = new java.util.TreeSet<>();
            values.keySet().stream().filter(oid -> oid.startsWith(prefix))
                    .map(oid -> oid.substring(prefix.length())).filter(s -> s.matches("[0-9]+"))
                    .forEach(indices::add);
            return indices;
        }

        Double diskKilobytes(String index, int low, int high, int legacy) {
            Double lo = number(UCD + "9.1." + low + "." + index);
            Double hi = number(UCD + "9.1." + high + "." + index);
            Double old = number(UCD + "9.1." + legacy + "." + index);
            if (lo != null && hi != null && lo < 4294967296d && hi < 4294967296d) return hi * 4294967296d + lo;
            // A saturated legacy Integer32 is not an accurate disk size.
            return old != null && old < Integer.MAX_VALUE ? old : null;
        }
    }
}
