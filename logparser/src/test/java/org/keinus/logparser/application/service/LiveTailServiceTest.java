package org.keinus.logparser.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.keinus.logparser.domain.model.LogEvent;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class LiveTailServiceTest {

    @Test
    void registerSessionSkipsClosedSession() {
        LiveTailService service = new LiveTailService();
        WebSocketSession closedSession = mock(WebSocketSession.class);

        when(closedSession.getId()).thenReturn("closed");
        when(closedSession.isOpen()).thenReturn(false);

        service.registerSession(closedSession);

        assertEquals(0, service.getSessionCount());
    }

    @Test
    void broadcastRemovesClosedAndFailingSessions() throws IOException {
        LiveTailService service = new LiveTailService();
        service.setEnabled(true);

        WebSocketSession healthy = mock(WebSocketSession.class);
        WebSocketSession stale = mock(WebSocketSession.class);
        WebSocketSession failing = mock(WebSocketSession.class);

        when(healthy.getId()).thenReturn("healthy");
        when(healthy.isOpen()).thenReturn(true);

        when(stale.getId()).thenReturn("stale");
        when(stale.isOpen()).thenReturn(true, false);

        when(failing.getId()).thenReturn("failing");
        when(failing.isOpen()).thenReturn(true);
        doThrow(new IOException("send failed")).when(failing).sendMessage(any(TextMessage.class));

        service.registerSession(healthy);
        service.registerSession(stale);
        service.registerSession(failing);

        LogEvent event = new LogEvent("log", "localhost", "test");
        event.setField("level", "INFO");
        service.broadcastLog(event);

        assertEquals(1, service.getSessionCount());
        verify(healthy).sendMessage(any(TextMessage.class));
    }
}
