package org.keinus.logparser.domain.input.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * HTTP 요청을 수신하여 전체 요청 내용을 단일 메시지로 처리하는 입력 어댑터입니다.
 * <p>
 * 이 클래스는 지정된 포트에서 {@link ServerSocket}을 열고 HTTP 클라이언트의 연결을 기다립니다.
 * 연결이 수립되면, HTTP 요청의 헤더와 본문을 포함한 전체 내용을 읽어 하나의
 * {@link Message} 객체로 생성합니다.
 * <p>
 * 이 어댑터는 주로 HTTP POST/PUT 요청을 통해 로그나 이벤트를 수신하는
 * 웹훅(Webhook) 형태의 엔드포인트로 사용될 수 있습니다.
 * {@code run()} 메서드는 블로킹 방식으로 동작하며, 새로운 요청이 들어올 때까지 대기합니다.
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see java.net.ServerSocket
 */
@Slf4j
public class HttpInputAdapter extends InputAdapter {
	private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024; // 10MB
	private static final int READ_BUFFER_SIZE = 8192;
	private static final String LINE_SEPARATOR = System.lineSeparator();

	private ServerSocket serverSocket;
	private final String localHostAddress;

	public HttpInputAdapter(InputAdapterConfig config) throws IOException {
		super(config);
		try {
			if (config.getPort() == null) {
				throw new IllegalArgumentException("Port is required for HTTP Input Adapter");
			}
			int port = config.getPort();
			serverSocket = new ServerSocket(port);
			localHostAddress = InetAddress.getLocalHost().getHostAddress();

			log.info("HTTP Input Adapter start at port {}", port);
		} catch (IOException e) {
			log.error("Failed to initialize HTTP input adapter: {}", e.getMessage(), e);
			throw e;
		}
	}

	private String read(Socket socket) throws IOException {
		StringBuilder sb = new StringBuilder(READ_BUFFER_SIZE);
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			int remaining = 0;

			// Read request line
			line = br.readLine();
			if (line == null) {
				return "";
			}
			sb.append(line);
			sb.append(LINE_SEPARATOR);

			while ((line = br.readLine()) != null) {
				sb.append(line);
				sb.append(LINE_SEPARATOR);
				if (line.equals(""))
					break;
				if (line.regionMatches(true, 0, "Content-Length:", 0, "Content-Length:".length())) {
					String contentLengthStr = line.substring("Content-Length:".length()).trim();
					try {
						remaining = Integer.parseInt(contentLengthStr);
						if (remaining < 0 || remaining > MAX_CONTENT_LENGTH) {
							throw new SecurityException("Content-Length 값이 허용 범위를 벗어남: " + remaining);
						}
					} catch (NumberFormatException e) {
						log.error("Invalid Content-Length header: {}", contentLengthStr);
						throw new IllegalArgumentException("Invalid Content-Length format", e);
					}
				}
			}

			if (remaining > 0) {
				char[] buffer = new char[Math.min(remaining, READ_BUFFER_SIZE)];
				int expectedLength = remaining;
				while (remaining > 0) {
					int rc = br.read(buffer, 0, Math.min(remaining, buffer.length));
					if (rc == -1) {
						break;
					}
					sb.append(buffer, 0, rc);
					remaining -= rc;
				}

				if (remaining != 0) {
					log.warn("Content-Length mismatch: expected {}, actual {}", expectedLength, expectedLength - remaining);
				}
			}

		}
		return sb.toString();
	}

	@Override
	public LogEvent run() {
		if (serverSocket == null)
			return null;
		try (Socket socket = serverSocket.accept()) {
			String msg = read(socket);
			return createLogEvent(msg, localHostAddress);
		} catch (IOException e) {
			log.error("Failed to read HTTP request: {}", e.getMessage(), e);
			return null;
		}
	}

	@Override
	public void close() throws IOException {
		try {
			if (serverSocket != null)
				serverSocket.close();
			serverSocket = null;
		} catch (IOException e) {
			log.error("Error: {}", e.getMessage());
		}
	}
}
