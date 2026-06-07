package org.keinus.logparser.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.keinus.logparser.domain.model.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class LiveTailService {

    private static final Logger log = LoggerFactory.getLogger(LiveTailService.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        log.info("LiveTailService enabled: {}", enabled);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void registerSession(WebSocketSession session) {
        if (!session.isOpen()) {
            log.debug("Skipping closed live tail session: {}", session.getId());
            return;
        }
        sessions.put(session.getId(), session);
        log.info("Live tail session registered: {}", session.getId());
    }

    public void removeSession(WebSocketSession session) {
        sessions.remove(session.getId());
        log.info("Live tail session removed: {}", session.getId());
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public void broadcastLog(LogEvent event) {
        if (!enabled.get() || sessions.isEmpty()) {
            return;
        }

        try {
            // Convert to a simple DTO or JSON map to avoid serializing internal state if needed
            // For now, serializing LogEvent directly or a subset
            String payload = objectMapper.writeValueAsString(Map.of(
                "timestamp", System.currentTimeMillis(), // or event timestamp
                "messageType", event.getMessageType(),
                "data", event.hasFields() ? event.getFields() : Map.of()
            ));

            TextMessage message = new TextMessage(payload);
            java.util.List<String> staleSessionIds = new java.util.ArrayList<>();

            for (WebSocketSession session : sessions.values()) {
                synchronized (session) {
                    if (!session.isOpen()) {
                        staleSessionIds.add(session.getId());
                        continue;
                    }
                    try {
                        session.sendMessage(message);
                    } catch (IOException | IllegalStateException e) {
                        staleSessionIds.add(session.getId());
                        log.warn("Failed to send message to session {}", session.getId());
                    }
                }
            }

            staleSessionIds.forEach(sessions::remove);
        } catch (Exception e) {
            log.error("Error broadcasting log event", e);
        }
    }
}
