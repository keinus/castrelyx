package org.keinus.logparser.infrastructure.config;

import org.keinus.logparser.interfaces.websocket.LiveTailHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final LiveTailHandler liveTailHandler;

    public WebSocketConfig(LiveTailHandler liveTailHandler) {
        this.liveTailHandler = liveTailHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveTailHandler, "/ws/tail")
                .setAllowedOrigins("*");
    }
}
