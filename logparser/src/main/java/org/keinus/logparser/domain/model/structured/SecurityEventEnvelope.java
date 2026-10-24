package org.keinus.logparser.domain.model.structured;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/** Stable event identity and insertion time, independent from display text. */
public final class SecurityEventEnvelope {
    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private SecurityEventEnvelope() {}

    public static String enrich(
            String payload,
            String asset,
            String type,
            String source,
            String eventType,
            Instant observedAt) {
        try {
            JsonNode node = JSON.readTree(payload);
            if (!(node instanceof ObjectNode object))
                throw new IllegalArgumentException("Event payload must be an object");
            String nativeId =
                    "SECURITY_ROUTER".equals(type) ? object.path("event_id").asText("") : "";
            if (nativeId.isBlank()) nativeId = object.path("dedup_key").asText("");
            if (nativeId.isBlank()) {
                ObjectNode stable = object.deepCopy();
                stable.remove(
                        java.util.List.of(
                                "stored_at", "received_at", "event_uid", "normalization_version"));
                nativeId =
                        observedAt
                                + "|"
                                + eventType
                                + "|"
                                + JSON.writeValueAsString(JSON.convertValue(stable, Map.class));
            }
            String identity = asset + "\u0000" + type + "\u0000" + source + "\u0000" + nativeId;
            object.put(
                    "event_uid",
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(identity.getBytes(StandardCharsets.UTF_8))));
            object.put("stored_at", Instant.now().toString());
            object.put("normalization_version", "security-2.0");
            return JSON.writeValueAsString(object);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to normalize security event envelope", e);
        }
    }
}
