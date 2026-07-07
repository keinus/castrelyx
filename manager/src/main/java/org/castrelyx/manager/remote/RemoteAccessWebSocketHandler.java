package org.castrelyx.manager.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.castrelyx.manager.auth.AuthException;
import org.castrelyx.manager.auth.AuthUser;
import org.castrelyx.manager.auth.ForbiddenException;
import org.castrelyx.manager.auth.LocalAuthProvider;
import org.castrelyx.manager.auth.Role;
import org.castrelyx.manager.web.AuthController;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RemoteAccessWebSocketHandler extends TextWebSocketHandler {
  private final RemoteAccessService remoteAccessService;
  private final LocalAuthProvider authProvider;
  private final ObjectMapper objectMapper;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final Map<String, SshTerminalBridge> bridges = new ConcurrentHashMap<>();
  private final Map<String, String> socketSessions = new ConcurrentHashMap<>();

  public RemoteAccessWebSocketHandler(RemoteAccessService remoteAccessService, LocalAuthProvider authProvider,
      ObjectMapper objectMapper) {
    this.remoteAccessService = remoteAccessService;
    this.authProvider = authProvider;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    AuthUser user;
    try {
      user = authProvider.currentUser(sessionCookie(session));
    } catch (AuthException exception) {
      session.close(CloseStatus.NOT_ACCEPTABLE.withReason(exception.getMessage()));
      return;
    }
    if (user.role() == Role.VIEWER) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason("viewer role cannot open SSH sessions"));
      return;
    }
    String sessionId = sessionId(session.getUri());
    if (sessionId == null) {
      session.close(CloseStatus.BAD_DATA.withReason("missing remote access session id"));
      return;
    }
    try {
      remoteAccessService.requireSessionAccess(sessionId, user);
    } catch (ForbiddenException exception) {
      session.close(CloseStatus.POLICY_VIOLATION.withReason(exception.getMessage()));
      return;
    }
    socketSessions.put(session.getId(), sessionId);
    send(session, "status", "임시 SSH 키 등록 대기 중", null);
    executor.submit(() -> connect(session, sessionId));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String sessionId = sessionId(session.getUri());
    if (sessionId == null) {
      return;
    }
    SshTerminalBridge bridge = bridges.get(sessionId);
    if (bridge == null) {
      return;
    }
    TerminalClientMessage clientMessage = objectMapper.readValue(message.getPayload(), TerminalClientMessage.class);
    if ("input".equals(clientMessage.type())) {
      bridge.input(clientMessage.data());
    } else if ("resize".equals(clientMessage.type())) {
      bridge.resize(clientMessage.cols() == null ? 80 : clientMessage.cols(), clientMessage.rows() == null ? 24 : clientMessage.rows());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    String sessionId = socketSessions.remove(session.getId());
    if (sessionId == null) {
      return;
    }
    SshTerminalBridge bridge = bridges.remove(sessionId);
    if (bridge != null) {
      bridge.close();
    }
    remoteAccessService.closeSession(sessionId, "websocket closed: " + status.getReason());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    String sessionId = socketSessions.get(session.getId());
    if (sessionId != null) {
      remoteAccessService.closeSession(sessionId, "websocket transport error: " + exception.getMessage());
    }
    super.handleTransportError(session, exception);
  }

  private void connect(WebSocketSession webSocketSession, String sessionId) {
    try {
      RemoteAccessSession accessSession = remoteAccessService.waitForAuthorization(sessionId);
      String privateKey = remoteAccessService.privateKey(sessionId);
      SshTerminalBridge bridge = new SshTerminalBridge(webSocketSession, objectMapper, accessSession, privateKey,
          remoteAccessService.allowUnknownHostKeys());
      bridges.put(sessionId, bridge);
      bridge.start(100, 30);
      remoteAccessService.markConnected(sessionId);
    } catch (Exception exception) {
      try {
        send(webSocketSession, "error", exception.getMessage(), null);
        webSocketSession.close(CloseStatus.SERVER_ERROR.withReason("SSH bridge failed"));
      } catch (Exception ignored) {
      }
      remoteAccessService.closeSession(sessionId, "SSH bridge failed: " + exception.getMessage());
    }
  }

  private String sessionCookie(WebSocketSession session) {
    Object cookies = session.getAttributes().get("cookies");
    if (cookies instanceof Cookie[] servletCookies) {
      for (Cookie cookie : servletCookies) {
        if (AuthController.SESSION_COOKIE.equals(cookie.getName())) {
          return cookie.getValue();
        }
      }
    }
    String cookieHeader = session.getHandshakeHeaders().getFirst("Cookie");
    if (cookieHeader == null) {
      return null;
    }
    for (String part : cookieHeader.split(";")) {
      String[] pieces = part.trim().split("=", 2);
      if (pieces.length == 2 && AuthController.SESSION_COOKIE.equals(pieces[0])) {
        return pieces[1];
      }
    }
    return null;
  }

  private static String sessionId(URI uri) {
    if (uri == null) {
      return null;
    }
    String[] parts = uri.getPath().split("/");
    for (int i = 0; i < parts.length - 2; i++) {
      if ("ssh-sessions".equals(parts[i])) {
        return parts[i + 1];
      }
    }
    return null;
  }

  private void send(WebSocketSession session, String type, String message, String data) throws Exception {
    synchronized (session) {
      if (session.isOpen()) {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(new TerminalServerMessage(type, message, data))));
      }
    }
  }

  private record TerminalClientMessage(String type, String data, Integer cols, Integer rows) {
  }

  private record TerminalServerMessage(String type, String message, String data) {
  }
}
