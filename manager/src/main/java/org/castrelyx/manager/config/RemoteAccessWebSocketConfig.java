package org.castrelyx.manager.config;

import org.castrelyx.manager.remote.RemoteAccessWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RemoteAccessWebSocketConfig implements WebSocketConfigurer {
  private final RemoteAccessWebSocketHandler handler;

  public RemoteAccessWebSocketConfig(RemoteAccessWebSocketHandler handler) {
    this.handler = handler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/api/remote-access/ssh-sessions/*/stream");
  }
}
