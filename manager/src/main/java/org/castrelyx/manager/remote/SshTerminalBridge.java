package org.castrelyx.manager.remote;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SshTerminalBridge implements AutoCloseable {
  private final WebSocketSession webSocketSession;
  private final ObjectMapper objectMapper;
  private final RemoteAccessSession accessSession;
  private final String privateKeyPem;
  private final boolean allowUnknownHostKeys;
  private SSHClient sshClient;
  private Session sshSession;
  private Session.Shell shell;
  private OutputStream shellInput;
  private volatile boolean closed;

  SshTerminalBridge(WebSocketSession webSocketSession, ObjectMapper objectMapper, RemoteAccessSession accessSession,
      String privateKeyPem, boolean allowUnknownHostKeys) {
    this.webSocketSession = webSocketSession;
    this.objectMapper = objectMapper;
    this.accessSession = accessSession;
    this.privateKeyPem = privateKeyPem;
    this.allowUnknownHostKeys = allowUnknownHostKeys;
  }

  void start(int cols, int rows) throws IOException {
    send("status", "SSH 연결 중", null);
    sshClient = new SSHClient();
    sshClient.setConnectTimeout(15000);
    if (allowUnknownHostKeys) {
      sshClient.addHostKeyVerifier(new PromiscuousVerifier());
    } else {
      sshClient.loadKnownHosts();
    }
    sshClient.connect(accessSession.targetHost(), accessSession.targetPort());
    Path keyPath = Files.createTempFile("castrelyx-webssh-", ".key");
    try {
      Files.writeString(keyPath, privateKeyPem, StandardCharsets.UTF_8);
      sshClient.authPublickey(accessSession.sshUser(), sshClient.loadKeys(keyPath.toString()));
    } finally {
      Files.deleteIfExists(keyPath);
    }
    sshSession = sshClient.startSession();
    sshSession.allocatePTY("xterm-256color", Math.max(40, cols), Math.max(12, rows), 0, 0, Collections.emptyMap());
    shell = sshSession.startShell();
    shellInput = shell.getOutputStream();
    send("status", "SSH 연결됨", null);
    pump("stdout", shell.getInputStream());
    pump("stderr", shell.getErrorStream());
  }

  void input(String data) throws IOException {
    if (closed || shellInput == null || data == null) {
      return;
    }
    shellInput.write(data.getBytes(StandardCharsets.UTF_8));
    shellInput.flush();
  }

  void resize(int cols, int rows) {
    if (closed || sshSession == null || cols <= 0 || rows <= 0) {
      return;
    }
    for (String methodName : new String[] {"changeWindowDimensions", "reqWindowChange"}) {
      try {
        Method method = sshSession.getClass().getMethod(methodName, int.class, int.class, int.class, int.class);
        method.invoke(sshSession, cols, rows, 0, 0);
        return;
      } catch (ReflectiveOperationException ignored) {
      }
    }
  }

  private void pump(String streamName, InputStream input) {
    Thread thread = new Thread(() -> {
      byte[] buffer = new byte[8192];
      try {
        int read;
        while (!closed && (read = input.read(buffer)) >= 0) {
          if (read > 0) {
            send("output", streamName, Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buffer, read)));
          }
        }
      } catch (IOException exception) {
        if (!closed) {
          sendQuietly("error", exception.getMessage(), null);
        }
      } finally {
        sendQuietly("closed", streamName + " closed", null);
      }
    }, "castrelyx-webssh-" + accessSession.id() + "-" + streamName);
    thread.setDaemon(true);
    thread.start();
  }

  private void send(String type, String message, String data) throws IOException {
    String encoded = objectMapper.writeValueAsString(new TerminalMessage(type, message, data));
    synchronized (webSocketSession) {
      if (webSocketSession.isOpen()) {
        webSocketSession.sendMessage(new TextMessage(encoded));
      }
    }
  }

  private void sendQuietly(String type, String message, String data) {
    try {
      send(type, message, data);
    } catch (IOException ignored) {
    }
  }

  @Override
  public void close() {
    closed = true;
    closeQuietly(shell);
    closeQuietly(sshSession);
    if (sshClient != null) {
      try {
        sshClient.disconnect();
      } catch (IOException ignored) {
      }
    }
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
    }
  }

  private record TerminalMessage(String type, String message, String data) {
  }
}
