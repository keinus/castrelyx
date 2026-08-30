package org.keinus.logparser.domain.input.model;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
	private static final int READ_TIMEOUT_MS = 30_000;
	private static final int MAX_HEADER_LINE_BYTES = 64 * 1024;
	private static final String LINE_SEPARATOR = System.lineSeparator();

	@FunctionalInterface
	protected interface ServerSocketProvider {
		ServerSocket create(InputAdapterConfig config, int port) throws IOException;
	}

	private volatile ServerSocket serverSocket;
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final AtomicReference<Socket> activeSocket = new AtomicReference<>();
	private final String localHostAddress;

	public HttpInputAdapter(InputAdapterConfig config) throws IOException {
		this(config, (adapterConfig, port) -> new ServerSocket(port), "HTTP");
	}

	protected HttpInputAdapter(InputAdapterConfig config, ServerSocketProvider serverSocketProvider, String adapterLabel) throws IOException {
		super(config);
		try {
			if (config.getPort() == null) {
				throw new IllegalArgumentException("Port is required for " + adapterLabel + " Input Adapter");
			}
			int port = config.getPort();
			serverSocket = serverSocketProvider.create(config, port);
			localHostAddress = InetAddress.getLocalHost().getHostAddress();

			log.info("{} Input Adapter start at port {}", adapterLabel, port);
		} catch (IOException e) {
			log.error("Failed to initialize {} input adapter: {}", adapterLabel, e.getMessage(), e);
			throw e;
		}
	}

	private String read(Socket socket) throws IOException {
		StringBuilder sb = new StringBuilder(READ_BUFFER_SIZE);
		try (InputStream input = new BufferedInputStream(socket.getInputStream(), READ_BUFFER_SIZE)) {
			String line;
			int remaining = 0;

			// Read request line
			line = readHeaderLine(input);
			if (line == null) {
				return "";
			}
			sb.append(line);
			sb.append(LINE_SEPARATOR);

			while ((line = readHeaderLine(input)) != null) {
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
				// Content-Length counts bytes, including multi-byte UTF-8 characters.
				byte[] body = input.readNBytes(remaining);
				sb.append(new String(body, StandardCharsets.UTF_8));
				if (body.length != remaining) {
					log.warn("Content-Length mismatch: expected {}, actual {}", remaining, body.length);
				}
			}

		}
		return sb.toString();
	}

	private String readHeaderLine(InputStream input) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		int value;
		while ((value = input.read()) != -1 && value != '\n') {
			if (line.size() >= MAX_HEADER_LINE_BYTES) {
				throw new IOException("HTTP header line exceeds " + MAX_HEADER_LINE_BYTES + " bytes");
			}
			line.write(value);
		}
		if (value == -1 && line.size() == 0) {
			return null;
		}
		byte[] bytes = line.toByteArray();
		int length = bytes.length;
		if (length > 0 && bytes[length - 1] == '\r') {
			length--;
		}
		return new String(bytes, 0, length, StandardCharsets.UTF_8);
	}

	@Override
	public LogEvent run() {
		ServerSocket listener = serverSocket;
		if (listener == null || closed.get())
			return null;
		try (Socket socket = listener.accept()) {
			activeSocket.set(socket);
			try {
				if (closed.get()) return null;
				socket.setSoTimeout(READ_TIMEOUT_MS);
				String msg = read(socket);
				return createLogEvent(msg, localHostAddress);
			} finally {
				activeSocket.compareAndSet(socket, null);
			}
		} catch (IOException e) {
			if (!closed.get()) log.error("Failed to read HTTP request: {}", e.getMessage(), e);
			return null;
		}
	}

	@Override
	public void close() throws IOException {
		closed.set(true);
		Socket accepted = activeSocket.getAndSet(null);
		try {
			if (accepted != null) accepted.close();
		} finally {
			ServerSocket listener = serverSocket;
			serverSocket = null;
			if (listener != null) listener.close();
		}
	}
}
