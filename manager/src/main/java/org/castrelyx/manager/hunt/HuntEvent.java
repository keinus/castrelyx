package org.castrelyx.manager.hunt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

final class HuntEvent {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern SSH_FROM = Pattern.compile("\\bfrom ([0-9a-fA-F:.]+)(?: port |\\s|$)");
  final Map<String, Object> event;
  final JsonNode data;
  final String id, asset, sourceId, sourceType, type, family, sourceIp, targetIp, targetPort, uid, protocol;
  final Instant at;
  HuntAssessment assessment;

  HuntEvent(Map<String, Object> event) {
    this.event = event;
    JsonNode root = JSON.valueToTree(event);
    data = root.path("raw").path("data").isObject() ? root.path("raw").path("data") : root;
    asset = text(event.get("assetUid")); sourceId = text(event.get("sourceId"));
    sourceType = text(event.get("sourceType")); type = text(event.get("eventType"));
    family = type.startsWith("zeek.") ? "zeek" : type.startsWith("suricata.") ? "suricata"
        : sourceType.equals("SECURITY_ROUTER") ? text(event.get("logFamily")) : "host";
    id = UUID.nameUUIDFromBytes((asset + "|" + sourceType + "|" + sourceId + "|"
        + event.getOrDefault("logKey", event.toString())).getBytes(StandardCharsets.UTF_8)).toString();
    String address = value("id.orig_h", "src_ip", "srcIp", "src");
    if (address.isBlank() && type.startsWith("auth.login.")) {
      var match = SSH_FROM.matcher(text(event.get("message")));
      if (match.find()) address = match.group(1);
    }
    sourceIp = address;
    targetIp = value("id.resp_h", "dest_ip", "destIp", "dst");
    targetPort = value("id.resp_p", "dest_port", "destPort", "p");
    uid = value("uid"); protocol = value("proto").toLowerCase(java.util.Locale.ROOT);
    Instant timestamp;
    try { timestamp = Instant.parse(text(event.get("observedAt"))); }
    catch (RuntimeException e) { timestamp = Instant.EPOCH; }
    at = timestamp;
  }

  String value(String... keys) {
    for (String key : keys) {
      String value = data.path(key).isValueNode() ? data.path(key).asText("") : "";
      if (value.isBlank()) value = text(event.get(key));
      if (!value.isBlank() && !value.equals("-")) return value;
    }
    return "";
  }

  String connectionKey() { return uid.isBlank() ? "" : asset + "|" + sourceType + "|" + sourceId + "|" + uid; }
  String sensorKey() { return asset + "|" + sourceType + "|" + sourceId; }
  String authKey() {
    String identity = sourceIp.isBlank() ? text(event.get("actor")) : sourceIp;
    return identity.isBlank() ? "" : sensorKey() + "|" + identity + "|" + targetIp;
  }
  String flowId() { return uid.isBlank() ? id : connectionKey(); }
  boolean loginFailure() {
    return type.equals("auth.login.failure") || type.equals("zeek.ssh") && value("auth_success").equals("false");
  }
  boolean loginSuccess() {
    return type.equals("auth.login.success") || type.equals("zeek.ssh") && value("auth_success").equals("true");
  }
  boolean unsuccessfulTcp() {
    return type.equals("zeek.conn") && protocol.equals("tcp") && !sourceIp.isBlank() && !targetIp.isBlank()
        && !targetPort.isBlank() && !multicast()
        && java.util.Set.of("S0", "REJ", "RSTOS0").contains(value("conn_state", "connectionState"));
  }
  boolean multicast() {
    return targetIp.toLowerCase(java.util.Locale.ROOT).startsWith("ff") || targetIp.equals("255.255.255.255")
        || targetIp.matches("(?:22[4-9]|23[0-9])\\..*");
  }
  static String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
