package org.keinus.logparser.domain.input.model;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;


/**
 * TCP 소켓을 통해 라인 단위로 메시지를 수신하는 입력 어댑터입니다.
 * <p>
 * 이 클래스는 지정된 포트에서 {@link ServerSocket}을 열고 클라이언트의 연결을 기다립니다.
 * 연결이 수립되면, 각 클라이언트를 별도 스레드에서 처리하여 persistent connection을 지원합니다.
 * 각 라인은 개행 문자(newline)로 구분됩니다.
 * <p>
 * {@code run()} 메서드는 블로킹 방식으로 동작하며, 새로운 클라이언트 연결이 들어올 때까지 대기합니다.
 * 각 클라이언트는 ExecutorService를 통해 별도 스레드에서 처리되어 동시 다중 연결을 지원합니다.
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see java.net.ServerSocket
 */
public class TcpInputAdapter extends InputAdapter {
	private static final Logger LOGGER = LoggerFactory.getLogger( TcpInputAdapter.class );
	private ServerSocket serverSocket;
	private int port = 0;
	private ExecutorService clientHandlerPool;
	private final AtomicInteger activeConnections = new AtomicInteger(0);
	private static final int MAX_CLIENTS = 100;  // 최대 동시 클라이언트 수
	private final BlockingQueue<LogEvent> eventQueue = new LinkedBlockingQueue<>(10000);

	// 재시도 관련 필드
	private int retryCount = 0;
	private static final int MAX_RETRIES = 3;
	private final AtomicBoolean terminated = new AtomicBoolean(false);
    
	public TcpInputAdapter(InputAdapterConfig config) throws IOException {
		super(config);
		try {
            if (config.getPort() == null) {
                throw new IllegalArgumentException("Port is required for TCP Input Adapter");
            }
            port = config.getPort();

            // 클라이언트 처리용 스레드 풀 초기화
            clientHandlerPool = Executors.newFixedThreadPool(MAX_CLIENTS,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("TcpClient-" + port + "-" + t.threadId());
                    t.setDaemon(true);
                    return t;
                });

            initServerSocket();

            LOGGER.info("TCP Input Adapter started at port {} with max {} concurrent clients",
                port, MAX_CLIENTS);
        } catch (IOException e) {
            LOGGER.error("Failed to initialize TCP Input Adapter: {}", e.getMessage(), e);
            throw e;
        }
	}

	private void initServerSocket() throws IOException {
		serverSocket = new ServerSocket(port);
		serverSocket.setReuseAddress(true);
		serverSocket.setSoTimeout(50);  // 50ms 타임아웃 (논블로킹 모드)
		LOGGER.debug("ServerSocket initialized on port {} with 50ms timeout", port);
	}

	@Override
	public LogEvent run() {
        // 종료 상태 확인
        if (terminated.get()) {
            LOGGER.debug("Adapter terminated, returning null");
            return null;
        }

        // 큐에서 이벤트를 폴링 (논블로킹, 100ms 타임아웃)
        try {
            LogEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                retryCount = 0;  // 정상 동작 시 재시도 카운터 리셋
                return event;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Interrupted while polling event queue");
            return null;
        }

        // 새 클라이언트 연결 수락 시도 (논블로킹)
        try {
            Socket clientSocket = serverSocket.accept();
            int currentConnections = activeConnections.incrementAndGet();

            LOGGER.debug("New client connected from {}. Active connections: {}",
                clientSocket.getInetAddress(), currentConnections);

            // 클라이언트를 별도 스레드에서 처리
            clientHandlerPool.submit(() -> handleClient(clientSocket));

            retryCount = 0;  // 성공 시 재시도 카운터 리셋

        } catch (java.net.SocketTimeoutException e) {
            // 타임아웃은 정상 - 계속 진행
        } catch(SocketException e) {
            if (terminated.get() || serverSocket == null || serverSocket.isClosed()) {
                LOGGER.debug("TCP input adapter is closed, skipping socket retry");
                return null;
            }

            retryCount++;

            if (retryCount >= MAX_RETRIES) {
                LOGGER.error("TcpInputAdapter failed after {} retries. Terminating adapter.", MAX_RETRIES);
                terminated.set(true);
                try {
                    close();
                } catch (IOException closeError) {
                    LOGGER.error("Error closing adapter after max retries: {}", closeError.getMessage(), closeError);
                }
                return null;
            }

            LOGGER.warn("Socket exception (retry {}/{}): {}", retryCount, MAX_RETRIES, e.getMessage());

            // Exponential backoff: 1s, 2s, 3s
            try {
                long sleepTime = retryCount * 1000L;
                LOGGER.info("Waiting {}ms before retry...", sleepTime);
                Thread.sleep(sleepTime);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                LOGGER.error("Interrupted during backoff wait", ie);
                return null;
            }

            try {
                initServerSocket();
                LOGGER.info("ServerSocket re-initialized successfully");
            } catch (IOException e1) {
                LOGGER.error("Failed to reinitialize ServerSocket (retry {}/{}): {}",
                    retryCount, MAX_RETRIES, e1.getMessage(), e1);
            }
		} catch (IOException e) {
            LOGGER.error("Error accepting client connection: {}", e.getMessage(), e);
        }

        return null;
    }

	/**
	 * 개별 클라이언트 연결을 처리합니다.
	 * Persistent connection을 지원하여 한 연결에서 여러 메시지를 수신합니다.
	 *
	 * @param socket 클라이언트 소켓
	 */
	private void handleClient(Socket socket) {
		String clientAddress = socket.getInetAddress().toString();
		long messagesReceived = 0;

		try {
			socket.setSoTimeout(300000);  // 5분 read timeout
			socket.setKeepAlive(true);
			socket.setTcpNoDelay(true);

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

				String line;
				// 연결이 끊어질 때까지 계속 메시지 읽기
				while ((line = reader.readLine()) != null) {
					if (!line.trim().isEmpty()) {
						LogEvent event = createLogEvent(line, clientAddress);
						if (event != null) {
							// 이벤트를 큐에 추가
							try {
								if (!eventQueue.offer(event, 1, TimeUnit.SECONDS)) {
									LOGGER.warn("Event queue full, dropping message from {}", clientAddress);
								} else {
									messagesReceived++;

									if (messagesReceived % 1000 == 0) {
										LOGGER.debug("Received {} messages from {}", messagesReceived, clientAddress);
									}
								}
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								LOGGER.warn("Interrupted while queuing event from {}", clientAddress);
								break;
							}
						}
					}
				}

				LOGGER.info("Client {} disconnected. Total messages received: {}",
					clientAddress, messagesReceived);

			}
		} catch (java.net.SocketTimeoutException e) {
			LOGGER.info("Client {} timed out after 5 minutes. Messages received: {}",
				clientAddress, messagesReceived);
		} catch (IOException e) {
			LOGGER.warn("Error handling client {}: {}. Messages received: {}",
				clientAddress, e.getMessage(), messagesReceived);
		} finally {
			try {
				if (!socket.isClosed()) {
					socket.close();
				}
			} catch (IOException e) {
				LOGGER.debug("Error closing client socket: {}", e.getMessage());
			}

			int remaining = activeConnections.decrementAndGet();
			LOGGER.debug("Client {} connection closed. Active connections: {}",
				clientAddress, remaining);
		}
	}
	
	@Override
	public void close() throws IOException {
		terminated.set(true);
		LOGGER.info("Closing TCP Input Adapter on port {}", port);

		// 1. ServerSocket 닫기 (새 연결 거부)
		if (serverSocket != null && !serverSocket.isClosed()) {
			try {
				serverSocket.close();
				LOGGER.info("ServerSocket closed");
			} catch (IOException e) {
				LOGGER.error("Error closing server socket: {}", e.getMessage(), e);
			}
		}

		// 2. 클라이언트 처리 스레드 풀 종료
		if (clientHandlerPool != null) {
			clientHandlerPool.shutdown();
			try {
				// 최대 10초 대기
				if (!clientHandlerPool.awaitTermination(10, TimeUnit.SECONDS)) {
					LOGGER.warn("Client handler pool did not terminate gracefully, forcing shutdown");
					clientHandlerPool.shutdownNow();

					// 강제 종료 후 다시 대기
					if (!clientHandlerPool.awaitTermination(5, TimeUnit.SECONDS)) {
						LOGGER.error("Client handler pool did not terminate after forced shutdown");
					}
				} else {
					LOGGER.info("Client handler pool terminated gracefully");
				}
			} catch (InterruptedException e) {
				LOGGER.error("Interrupted while waiting for client handler pool termination", e);
				clientHandlerPool.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

		LOGGER.info("TCP Input Adapter closed. Final active connections: {}", activeConnections.get());
	}
}
