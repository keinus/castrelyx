package org.keinus.logparser.domain.input.model;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import lombok.extern.slf4j.Slf4j;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * UDP 데이터그램 패킷을 통해 메시지를 수신하는 입력 어댑터입니다.
 * <p>
 * 이 클래스는 지정된 포트에서 {@link DatagramSocket}을 열고 UDP 패킷을 기다립니다.
 * 패킷을 수신하면, 그 내용을 문자열로 변환하여 {@link Message} 객체를 생성합니다.
 * 각 데이터그램 패킷은 하나의 메시지로 처리됩니다.
 * <p>
 * {@code run()} 메서드는 블로킹 방식으로 동작하며, 새로운 UDP 패킷이 도착할 때까지 대기합니다.
 * Syslog와 같은 비연결성 프로토콜을 통해 로그를 수신하는 데 주로 사용됩니다.
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see java.net.DatagramSocket
 * @see java.net.DatagramPacket
 */
@Slf4j
public class UdpInputAdapter extends InputAdapter {
	private static final int MAX_PACKET_SIZE = 1600; 
	private DatagramSocket serverSocket = null;
	private final byte[] receiveBuffer = new byte[MAX_PACKET_SIZE];
	private final DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

	public UdpInputAdapter(InputAdapterConfig config) throws IOException {
		super(config);

		if (config.getPort() == null) {
			throw new IllegalArgumentException("Port is required for UDP Input Adapter");
		}
		int port = config.getPort();
		try {
			serverSocket = new DatagramSocket(port);
			serverSocket.setSoTimeout(5000);
			log.info("UDP Input Adapter started at port {}", port);
		} catch (SocketException e) {
			log.error("Failed to initialize UDP socket on port {}: {}", port, e.getMessage(), e);
			throw new IOException("Failed to initialize UDP socket on port " + port, e);
		}
	}

	@Override
	public LogEvent run() {
		if(serverSocket == null)
			return null;

		try {
			receivePacket.setLength(receiveBuffer.length);
			serverSocket.receive(receivePacket);
			int actualLength = receivePacket.getLength();
			if (actualLength > MAX_PACKET_SIZE) {
				throw new SecurityException("패킷 크기가 제한을 초과했습니다");
			}

			String payload = new String(receiveBuffer, 0, actualLength, StandardCharsets.UTF_8);

			String host = receivePacket.getAddress().toString();
			return createLogEvent(payload, host);
		} catch (SocketTimeoutException e) {
			return null;
		} catch (SocketException e) {
			if (serverSocket == null || serverSocket.isClosed()) {
				log.debug("UDP socket closed, stopping receive loop");
			} else {
				log.warn("UDP socket error: {}", e.getMessage());
			}
		} catch (IOException e) {
			log.warn("Failed to receive UDP packet: {}", e.getMessage());
		}
		return null;
	}

	@Override
	public void close() throws IOException {
		if (serverSocket != null) {
			serverSocket.close();
			serverSocket = null;
		}
	}

}
