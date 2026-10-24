package org.keinus.logparser.domain.model.structured;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class SecurityEventEnvelopeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void replayHasStableIdentityAndSeparateInsertionTime() throws Exception {
        Instant observed = Instant.parse("2026-10-22T00:00:00Z");
        var first =
                json.readTree(
                        SecurityEventEnvelope.enrich(
                                "{\"event_id\":4625,\"dedup_key\":\"record-1\",\"message\":\"display\"}",
                                "host",
                                "AGENT",
                                "agent",
                                "auth.login.failure",
                                observed));
        var retry =
                json.readTree(
                        SecurityEventEnvelope.enrich(
                                first.toString(),
                                "host",
                                "AGENT",
                                "agent",
                                "auth.login.failure",
                                observed));
        assertThat(first.path("event_uid").asText())
                .hasSize(64)
                .isEqualTo(retry.path("event_uid").asText());
        assertThat(Instant.parse(first.path("stored_at").asText())).isAfter(observed);
        var second =
                json.readTree(
                        SecurityEventEnvelope.enrich(
                                "{\"event_id\":4625,\"dedup_key\":\"record-2\"}",
                                "host",
                                "AGENT",
                                "agent",
                                "auth.login.failure",
                                observed));
        assertThat(second.path("event_uid")).isNotEqualTo(first.path("event_uid"));
    }

    @Test
    void fallbackIdentityIsIndependentFromJsonPropertyOrderAndInsertionMetadata() throws Exception {
        var first =
                json.readTree(
                        SecurityEventEnvelope.enrich(
                                "{\"a\":1,\"b\":2}", "h", "AGENT", "s", "type", Instant.EPOCH));
        var second =
                json.readTree(
                        SecurityEventEnvelope.enrich(
                                "{\"b\":2,\"stored_at\":\"later\",\"a\":1}",
                                "h",
                                "AGENT",
                                "s",
                                "type",
                                Instant.EPOCH));
        assertThat(first.path("event_uid")).isEqualTo(second.path("event_uid"));
    }
}
