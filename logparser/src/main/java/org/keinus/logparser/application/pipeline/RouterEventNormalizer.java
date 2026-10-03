package org.keinus.logparser.application.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.model.LogEvent;
import java.time.Instant;
import java.util.*;

/** Contract between the authenticated router input and the canonical writer. */
public final class RouterEventNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private RouterEventNormalizer() {}
    public static void normalize(LogEvent event) {
        var envelope = JSON.valueToTree(event.getField("syslog_MESSAGE"));
        if (!"security-router.event/v1".equals(envelope.path("schema_version").asText())) throw new IllegalArgumentException("Unsupported router schema_version");
        String source = envelope.path("source_id").asText();
        if (event.getAuthenticatedSourceId() == null || !source.equals(event.getAuthenticatedSourceId()) || event.getAuthenticatedAssetUid() == null) throw new IllegalArgumentException("Router source_id does not match registered TLS identity");
        var raw = envelope.path("payload");
        String id = raw.path("id").asText();
        String kind = raw.path("kind").asText();
        String family = envelope.path("log_family").asText();
        if (!raw.isObject() || !id.matches("[A-Za-z0-9_.:-]{1,128}") || kind.isBlank() || kind.length()>160 || !Set.of("audit","zeek").contains(family)) throw new IllegalArgumentException("Invalid router event identity or family");
        Instant at = Instant.parse(raw.path("at").asText());
        Map<String,Object> payload = new LinkedHashMap<>();
        if (family.equals("audit") && (kind.startsWith("incident.") || kind.startsWith("quarantine.") || kind.startsWith("authentication.") || kind.startsWith("authorization."))) family="security";
        String result = raw.path("result").asText();
        String severity = Set.of("failed","denied","rejected","error").contains(result.toLowerCase(Locale.ROOT)) ? "ERROR" : "INFO";
        if (severity.equals("INFO") && (kind.equals("zeek.notice") || kind.equals("zeek.weird") || kind.equals("incident.open"))) severity="WARNING";
        String message = family.equals("zeek") ? kind+" "+raw.path("data").toString() : String.join(" ", kind,raw.path("target").asText(), result, raw.path("detail").asText()).trim();
        payload.put("event_id",id); payload.put("dedup_key",source.length()+":"+source+":"+id);
        payload.put("event_type",kind); payload.put("log_family",family);
        payload.put("event_category",family); payload.put("severity",severity);
        payload.put("message",message.substring(0, Math.min(message.length(),8192)));
        payload.put("source_name", "security-router/"+family); payload.put("provider","security-router");
        payload.put("actor",raw.path("actor").path("id").asText());
        payload.put("action",kind); payload.put("outcome",result);
        payload.put("job_id",raw.path("jobId").asText()); payload.put("target",raw.path("target").asText());
        payload.put("received_at",event.getTimestamp().toString());
        payload.put("raw",JSON.convertValue(raw,Map.class));
        event.getFields().clear();
        event.setFields(Map.of("source_type","SECURITY_ROUTER", "source_id",source,
                "asset_uid",event.getAuthenticatedAssetUid(), "item_kind","event", "item_type",kind,
                "item_id",id,"observed_at",at.toString(), "payload",payload));
    }
}
