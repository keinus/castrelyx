package org.keinus.logparser.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogEventTest {

    @Test
    void outputPayloadIncludesOriginTextOnlyWhenRequested() {
        LogEvent event = new LogEvent("raw message", "localhost", "access");
        event.setField("level", "INFO");

        Map<String, Object> withoutOrigin = event.toOutputMap(false);
        Map<String, Object> withOrigin = event.toOutputMap(true);

        assertEquals("INFO", withoutOrigin.get("level"));
        assertFalse(withoutOrigin.containsKey("origin_text"));
        assertEquals("raw message", withOrigin.get("origin_text"));
        assertEquals("INFO", withOrigin.get("level"));
    }

    @Test
    void cachedPayloadIsInvalidatedWhenOriginTextChanges() {
        LogEvent event = new LogEvent("before", "localhost", "access");

        String initialPayload = event.toOutputJson(true);
        event.setOriginalText("after");
        String updatedPayload = event.toOutputJson(true);

        assertTrue(initialPayload.contains("\"origin_text\":\"before\""));
        assertTrue(updatedPayload.contains("\"origin_text\":\"after\""));
    }

    @Test
    void prepareOutputPayloadMaintainsSeparateCachesForOriginVariants() {
        LogEvent event = new LogEvent("raw", "localhost", "access");
        event.setField("service", "api");

        event.prepareOutputPayload();
        String cachedWithoutOrigin = event.toOutputJson(false);
        String uncachedWithOrigin = event.toOutputJson(true);

        assertTrue(cachedWithoutOrigin.contains("\"service\":\"api\""));
        assertFalse(cachedWithoutOrigin.contains("origin_text"));
        assertTrue(uncachedWithOrigin.contains("\"origin_text\":\"raw\""));

        event.prepareOutputPayload(true);
        String cachedWithOrigin = event.toOutputJson(true);

        assertTrue(cachedWithOrigin.contains("\"origin_text\":\"raw\""));
        assertTrue(cachedWithOrigin.contains("\"service\":\"api\""));
    }
}
