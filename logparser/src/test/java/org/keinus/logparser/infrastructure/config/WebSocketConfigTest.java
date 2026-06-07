package org.keinus.logparser.infrastructure.config;

import org.keinus.logparser.interfaces.websocket.LiveTailHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock
    private LiveTailHandler liveTailHandler;

    @Mock
    private WebSocketHandlerRegistry registry;

    @Mock
    private WebSocketHandlerRegistration registration;

    @InjectMocks
    private WebSocketConfig webSocketConfig;

    @Test
    void shouldRegisterWebSocketHandlers() {
        when(registry.addHandler(eq(liveTailHandler), eq("/ws/tail"))).thenReturn(registration);
        when(registration.setAllowedOrigins(anyString())).thenReturn(registration);

        webSocketConfig.registerWebSocketHandlers(registry);

        verify(registry).addHandler(liveTailHandler, "/ws/tail");
        verify(registration).setAllowedOrigins("*");
    }
}
