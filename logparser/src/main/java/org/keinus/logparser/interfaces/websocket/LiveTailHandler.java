package org.keinus.logparser.interfaces.websocket;

import org.keinus.logparser.application.service.LiveTailService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class LiveTailHandler implements WebSocketHandler {

    private final LiveTailService liveTailService;

    public LiveTailHandler(LiveTailService liveTailService) {
        this.liveTailService = liveTailService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        liveTailService.registerSession(session);
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        // No incoming messages expected from client for now
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        liveTailService.removeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
        liveTailService.removeSession(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }
}
