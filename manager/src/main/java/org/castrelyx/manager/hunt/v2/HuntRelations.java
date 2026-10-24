package org.castrelyx.manager.hunt.v2;

import java.net.InetAddress;
import java.time.Instant;
import java.util.*;

/** Evidence-backed observations. Shared addresses, PIDs or Community IDs are not host identity. */
public final class HuntRelations {
  private HuntRelations() {}

  public static final int INPUT_LIMIT = 10_000;
  public static final int NODE_LIMIT = 1_000;
  public static final int EDGE_LIMIT = 2_000;
  private static final int EVIDENCE_LIMIT = 20;
  public static final List<String> FIELDS =
      List.of(
          "observed_asset_uid",
          "asset_uid",
          "source_id",
          "source_type",
          "event_type",
          "process_id",
          "process_guid",
          "parent_guid",
          "process_pid",
          "parent_pid",
          "process_started_at",
          "parent_started_at",
          "boot_id",
          "process_name",
          "parent_name",
          "src_ip",
          "dst_ip",
          "src_port",
          "dst_port",
          "protocol",
          "connection_uid",
          "connection_started_at",
          "community_id",
          "network_namespace");

  private static final class Node {
    final String id, kind;
    String label;
    final Map<String, Object> attributes = new LinkedHashMap<>();
    final Set<String> evidence = new LinkedHashSet<>();
    final Set<Instant> starts = new TreeSet<>();
    Instant first, last, end;
    boolean observed;

    Node(String id, String kind, String label, Map<String, Object> attributes) {
      this.id = id;
      this.kind = kind;
      this.label = label;
      this.attributes.putAll(attributes);
    }

    void observe(Map<String, Object> row, boolean actual) {
      String event = text(row, "id");
      evidence.add(event);
      Instant at = instant(text(row, "at"));
      if (at != null) {
        if (first == null || at.isBefore(first)) first = at;
        if (last == null || at.isAfter(last)) last = at;
      }
      observed |= actual;
    }

    Instant start() {
      return starts.isEmpty() ? null : starts.iterator().next();
    }

    boolean ambiguous() {
      return starts.size() > 1
          || start() != null
              && (end != null && end.isBefore(start()) || first != null && first.isBefore(start()));
    }

    boolean alive(Instant at) {
      return !ambiguous()
          && at != null
          && (start() == null || !at.isBefore(start()))
          && (end == null || at.isBefore(end));
    }

    Map<String, Object> output() {
      var result = new LinkedHashMap<String, Object>();
      result.put("id", id);
      result.put("kind", kind);
      result.put("label", label);
      result.put("attributes", attributes);
      result.put("referenceOnly", !observed);
      result.put("ambiguous", ambiguous());
      result.put("firstSeen", first == null ? null : first.toString());
      result.put("lastSeen", last == null ? null : last.toString());
      result.put("startedAt", start() == null ? null : start().toString());
      result.put("endedAt", end == null ? null : end.toString());
      result.put("eventIds", evidence.stream().limit(EVIDENCE_LIMIT).toList());
      result.put("evidenceCount", evidence.size());
      return result;
    }
  }

  private static final class Edge {
    final String id, from, to, kind;
    final Set<String> evidence = new LinkedHashSet<>();

    Edge(String from, String to, String kind) {
      this.from = from;
      this.to = to;
      this.kind = kind;
      id = key("edge", from, to, kind);
    }

    Map<String, Object> output() {
      return Map.of(
          "id",
          id,
          "from",
          from,
          "to",
          to,
          "kind",
          kind,
          "eventIds",
          evidence.stream().limit(EVIDENCE_LIMIT).toList(),
          "evidenceCount",
          evidence.size());
    }
  }

  public static Map<String, Object> build(List<Map<String, Object>> input, long matchedEvents) {
    var nodes = new LinkedHashMap<String, Node>();
    var edges = new LinkedHashMap<String, Edge>();
    var issues = new TreeMap<String, Integer>();
    var samples = new ArrayList<Map<String, String>>();
    var seen = new HashSet<String>();
    var rows = new ArrayList<Map<String, Object>>();
    for (var row : input.stream().limit(INPUT_LIMIT).toList()) {
      String id = text(row, "id");
      if (!id.matches("[a-f0-9]{64}") || instant(text(row, "at")) == null) {
        issue(issues, samples, row, "INVALID_EVENT_ID_OR_TIME");
      } else if (seen.add(id)) rows.add(row);
    }
    rows.sort(
        Comparator.comparing((Map<String, Object> row) -> instant(text(row, "at")))
            .thenComparing(row -> text(row, "id")));
    var parents = new HashMap<String, Set<String>>();
    for (var row : rows) {
      String identity = processKey(row, false);
      if (identity == null) {
        if (text(row, "event_type").startsWith("process."))
          issue(issues, samples, row, "PROCESS_IDENTITY_MISSING");
        continue;
      }
      var node = nodes.computeIfAbsent(identity, ignored -> processNode(row, false, identity));
      node.observe(row, true);
      if (!text(row, "process_name").isEmpty()) node.label = label(text(row, "process_name"));
      Instant start = instant(text(row, "process_started_at"));
      if (start == null && "process.start".equals(text(row, "event_type")))
        start = instant(text(row, "at"));
      if (start != null) node.starts.add(start);
      if ("process.exit".equals(text(row, "event_type"))) {
        Instant at = instant(text(row, "at"));
        if (node.end == null || at.isBefore(node.end)) node.end = at;
      }
      String parent = processKey(row, true);
      if (parent != null) parents.computeIfAbsent(identity, ignored -> new HashSet<>()).add(parent);
    }
    // Resolve every declared parent generation before evaluating any edge; a late contradictory
    // reference must not leave an earlier, already accepted edge in the result.
    for (var row : rows) {
      String parentKey = processKey(row, true);
      if (processKey(row, false) == null || parentKey == null) continue;
      var parent = nodes.computeIfAbsent(parentKey, ignored -> processNode(row, true, parentKey));
      Instant started = instant(text(row, "parent_started_at"));
      if (started != null) parent.starts.add(started);
    }
    for (var row : rows) {
      String childKey = processKey(row, false), parentKey = processKey(row, true);
      Node child = nodes.get(childKey);
      if (child == null || parentKey == null) continue;
      if (childKey.equals(parentKey) || parentCycle(childKey, parents)) {
        issue(issues, samples, row, "PARENT_CYCLE");
        continue;
      }
      if (parents.getOrDefault(childKey, Set.of()).size() != 1) {
        issue(issues, samples, row, "CONFLICTING_PARENT_REFERENCE");
        continue;
      }
      var parent = nodes.computeIfAbsent(parentKey, ignored -> processNode(row, true, parentKey));
      Instant birth = child.start() == null ? instant(text(row, "at")) : child.start();
      if (child.ambiguous() || !parent.alive(birth)) {
        issue(issues, samples, row, "PROCESS_LIFETIME_CONFLICT");
        continue;
      }
      parent.observe(row, false);
      if (!parent.observed) issue(issues, samples, row, "PARENT_NOT_OBSERVED");
      edge(edges, parent.id, child.id, "PARENT_OF", row);
    }
    for (var row : rows) {
      String src = address(text(row, "src_ip")), dst = address(text(row, "dst_ip"));
      String sport = port(row.get("src_port")), dport = port(row.get("dst_port"));
      String protocol = text(row, "protocol").toLowerCase(Locale.ROOT);
      String uid = text(row, "connection_uid");
      boolean tuple =
          !src.isEmpty()
              && !dst.isEmpty()
              && !sport.isEmpty()
              && !dport.isEmpty()
              && protocol.matches("[a-z0-9_-]{1,32}");
      if (!tuple && uid.isEmpty()) {
        if (Set.of(
                "process.network",
                "network.connection",
                "network.flow",
                "zeek.conn",
                "suricata.flow",
                "suricata.alert")
            .contains(text(row, "event_type")))
          issue(issues, samples, row, "CONNECTION_IDENTITY_MISSING");
        continue;
      }
      String scope = scope(row);
      // An ID without an observed boot and explicit start is not a reusable session identity.
      // Keep each evidence record separate in that case; never correlate by Community ID alone.
      Instant started = instant(text(row, "connection_started_at"));
      boolean session =
          !uid.isEmpty()
              && started != null
              && !text(row, "boot_id").isEmpty()
              && !scope.isEmpty()
              && tuple;
      String instance = session ? key(text(row, "boot_id"), started.toString()) : text(row, "id");
      String connection = key("connection", scope, uid, src, sport, dst, dport, protocol, instance);
      var attrs = observer(row);
      attrs.put("connection_uid", uid);
      attrs.put("community_id", text(row, "community_id"));
      attrs.put("src_ip", src);
      attrs.put("dst_ip", dst);
      attrs.put("src_port", sport);
      attrs.put("dst_port", dport);
      attrs.put("protocol", protocol);
      attrs.put(
          "identityBasis",
          session ? "BOOT_START_ID_TUPLE" : tuple ? "EVENT_5_TUPLE" : "EVENT_CONNECTION_ID");
      var flow =
          nodes.computeIfAbsent(
              connection,
              ignored ->
                  new Node(
                      connection,
                      "CONNECTION",
                      tuple
                          ? src + ":" + sport + " → " + dst + ":" + dport + " · " + protocol
                          : "연결 " + label(uid),
                      attrs));
      flow.observe(row, true);
      if (started != null && started.isAfter(instant(text(row, "at")))) {
        issue(issues, samples, row, "CONNECTION_TIME_CONFLICT");
        continue;
      }
      if (tuple) {
        Node origin = endpoint(nodes, row, src, sport, protocol);
        Node target = endpoint(nodes, row, dst, dport, protocol);
        edge(edges, origin.id, flow.id, "OBSERVED_FROM", row);
        edge(edges, flow.id, target.id, "OBSERVED_TO", row);
      } else issue(issues, samples, row, "CONNECTION_TUPLE_MISSING");
      Node process = nodes.get(processKey(row, false));
      if (process != null && "process.network".equals(text(row, "event_type"))) {
        if (process.alive(instant(text(row, "at"))))
          edge(edges, process.id, flow.id, "PROCESS_NETWORK", row);
        else issue(issues, samples, row, "PROCESS_LIFETIME_CONFLICT");
      }
    }
    for (var node : nodes.values()) {
      if (!"PROCESS".equals(node.kind) || !node.ambiguous()) continue;
      issues.merge("PROCESS_IDENTITY_CONFLICT", 1, Integer::sum);
      if (samples.size() < 100 && !node.evidence.isEmpty())
        samples.add(
            Map.of(
                "eventId", node.evidence.iterator().next(), "reason", "PROCESS_IDENTITY_CONFLICT"));
    }
    var selected = nodes.values().stream().limit(NODE_LIMIT).toList();
    var selectedIds = new HashSet<String>();
    selected.forEach(n -> selectedIds.add(n.id));
    var selectedEdges =
        edges.values().stream()
            .filter(e -> selectedIds.contains(e.from) && selectedIds.contains(e.to))
            .limit(EDGE_LIMIT)
            .toList();
    long unexamined = Math.max(0, matchedEvents - rows.size());
    if (matchedEvents > input.size() && input.size() <= INPUT_LIMIT)
      issues.put("SELECTION_CHANGED_OR_EXPIRED", 1);
    boolean truncated =
        unexamined > 0
            || input.size() > INPUT_LIMIT
            || nodes.size() > NODE_LIMIT
            || selectedEdges.size() < edges.size();
    return Map.of(
        "version",
        "relations-1.0.1",
        "nodes",
        selected.stream().map(Node::output).toList(),
        "edges",
        selectedEdges.stream().map(Edge::output).toList(),
        "issues",
        issues,
        "issueSamples",
        samples,
        "coverage",
        Map.of(
            "matchedEvents",
            matchedEvents,
            "evaluatedEvents",
            rows.size(),
            "inputLimit",
            INPUT_LIMIT,
            "nodeLimit",
            NODE_LIMIT,
            "edgeLimit",
            EDGE_LIMIT,
            "omittedNodes",
            nodes.size() - selected.size(),
            "omittedEdges",
            edges.size() - selectedEdges.size(),
            "truncated",
            truncated,
            "complete",
            !truncated && issues.isEmpty(),
            "unexaminedEvents",
            unexamined));
  }

  private static boolean parentCycle(String child, Map<String, Set<String>> parents) {
    var seen = new HashSet<String>();
    String current = child;
    while (current != null) {
      if (!seen.add(current)) return true;
      var next = parents.getOrDefault(current, Set.of());
      current = next.size() == 1 ? next.iterator().next() : null;
    }
    return false;
  }

  private static Node endpoint(
      Map<String, Node> nodes, Map<String, Object> row, String ip, String port, String protocol) {
    String scope = scope(row);
    String id = key("endpoint", scope.isEmpty() ? text(row, "id") : scope, ip, port, protocol);
    var attributes = observer(row);
    attributes.put("address", ip);
    attributes.put("port", port);
    attributes.put("protocol", protocol);
    var result =
        nodes.computeIfAbsent(id, ignored -> new Node(id, "ENDPOINT", ip + ":" + port, attributes));
    result.observe(row, true);
    return result;
  }

  private static Node processNode(Map<String, Object> row, boolean parent, String identity) {
    var attributes = observer(row);
    String guid = guid(text(row, parent ? "parent_guid" : "process_guid"));
    if (!parent && guid.isEmpty()) guid = guid(text(row, "process_id"));
    String pid = pid(text(row, parent ? "parent_pid" : "process_pid"));
    attributes.put("process_guid", guid);
    attributes.put("process_pid", pid);
    attributes.put("identityBasis", guid.isEmpty() ? "BOOT_PID_START" : "GUID");
    String name = text(row, parent ? "parent_name" : "process_name");
    return new Node(
        identity,
        "PROCESS",
        name.isEmpty() ? (!pid.isEmpty() ? "PID " + pid : guid) : label(name),
        attributes);
  }

  private static String processKey(Map<String, Object> row, boolean parent) {
    String scope = scope(row);
    if (scope.isEmpty()) return null;
    String guid = guid(text(row, parent ? "parent_guid" : "process_guid"));
    if (!parent && guid.isEmpty()) guid = guid(text(row, "process_id"));
    if (!guid.isEmpty()) return key("process-guid", scope, text(row, "boot_id"), guid);
    String boot = text(row, "boot_id"), pid = pid(text(row, parent ? "parent_pid" : "process_pid"));
    Instant start = instant(text(row, parent ? "parent_started_at" : "process_started_at"));
    return boot.isEmpty() || pid.isEmpty() || start == null
        ? null
        : key("process-pid", scope, boot, pid, start.toString());
  }

  private static Map<String, Object> observer(Map<String, Object> row) {
    var result = new LinkedHashMap<String, Object>();
    for (String field :
        List.of(
            "observed_asset_uid",
            "asset_uid",
            "source_type",
            "source_id",
            "boot_id",
            "network_namespace")) result.put(field, text(row, field));
    return result;
  }

  private static String scope(Map<String, Object> row) {
    String asset = text(row, "observed_asset_uid"),
        source = text(row, "source_id"),
        type = text(row, "source_type");
    return asset.isEmpty() || source.isEmpty() || type.isEmpty()
        ? ""
        : key(asset, source, type, text(row, "network_namespace"));
  }

  private static void edge(
      Map<String, Edge> edges, String from, String to, String kind, Map<String, Object> row) {
    var edge = new Edge(from, to, kind);
    edges.computeIfAbsent(edge.id, ignored -> edge).evidence.add(text(row, "id"));
  }

  private static void issue(
      Map<String, Integer> issues,
      List<Map<String, String>> samples,
      Map<String, Object> row,
      String reason) {
    issues.merge(reason, 1, Integer::sum);
    if (samples.size() < 100) samples.add(Map.of("eventId", text(row, "id"), "reason", reason));
  }

  private static String text(Map<String, Object> row, String field) {
    Object value = row.get(field);
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.length() > 2048 ? "" : text;
  }

  private static String label(String value) {
    return value.substring(0, Math.min(180, value.length()));
  }

  private static String key(String... values) {
    var text = new StringBuilder();
    for (String value : values) text.append(value.length()).append(':').append(value);
    return HuntWorkspace.hash(text.toString());
  }

  private static String guid(String value) {
    String result =
        value.startsWith("{") && value.endsWith("}")
            ? value.substring(1, value.length() - 1)
            : value;
    result = result.toLowerCase(Locale.ROOT);
    return result.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
            && !result.equals("00000000-0000-0000-0000-000000000000")
        ? result
        : "";
  }

  private static String pid(String value) {
    try {
      if (!value.matches("(?:[0-9]{1,10}|0[xX][0-9a-fA-F]{1,8})")) return "";
      long n =
          value.startsWith("0x") || value.startsWith("0X")
              ? Long.parseLong(value.substring(2), 16)
              : Long.parseLong(value);
      return n > 0 && n <= 4294967295L ? Long.toString(n) : "";
    } catch (NumberFormatException ignored) {
      return "";
    }
  }

  private static String port(Object value) {
    try {
      double number = value == null ? Double.NaN : Double.parseDouble(String.valueOf(value));
      return Double.isFinite(number)
              && number == Math.rint(number)
              && number >= 0
              && number <= 65535
          ? Long.toString((long) number)
          : "";
    } catch (NumberFormatException ignored) {
      return "";
    }
  }

  private static String address(String value) {
    try {
      if (!value.matches("[0-9a-fA-F:.]{1,45}") || !value.contains(".") && !value.contains(":"))
        return "";
      if (!value.contains(":")) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return "";
        for (String part : parts)
          if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return "";
      }
      return InetAddress.getByName(value).getHostAddress();
    } catch (Exception ignored) {
      return "";
    }
  }

  private static Instant instant(String value) {
    try {
      if (value.isEmpty()) return null;
      String iso = value.replace(' ', 'T');
      return Instant.parse(iso.matches(".*(?:Z|[+-][0-9]{2}:[0-9]{2})$") ? iso : iso + "Z");
    } catch (Exception ignored) {
      return null;
    }
  }
}
