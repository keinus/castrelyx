package org.keinus.logparser.domain.input.model;

import java.io.Closeable;
import java.io.IOException;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

import lombok.Getter;

/**
 * 데이터 소스(source)로부터 로그 이벤트를 수신하는 모든 입력 어댑터의 추상 기반 클래스입니다.
 * <p>
 * 이 클래스는 다양한 입력 방식(예: File, TCP, UDP, Kafka)을 표준화된 인터페이스로 추상화합니다.
 * 모든 구체적인 입력 어댑터는 이 클래스를 상속받아 {@link #run()} 메서드를 구현해야 합니다.
 * <p>
 * 각 어댑터는 생성 시 타입 안전한 설정 객체({@code InputAdapterConfig})를 통해 초기화되며,
 * 고유한 메시지 타입({@code messagetype})을 가집니다.
 *
 * @see java.io.Closeable
 * @see org.keinus.logparser.domain.configuration.model.InputAdapterConfig
 */
public abstract class InputAdapter implements Closeable {
	@Getter
	private Long id;
	@Getter
	private String messageType = "";
	@Getter
	private String name;
	@Getter
	private String sourceHost;

	protected InputAdapter(InputAdapterConfig config) throws IOException {
		if (config == null) {
			throw new IOException("Configuration not found.");
		}
		this.id = config.getId();
		this.messageType = config.getMessagetype();
		this.sourceHost = config.getHost() != null ? config.getHost() : "localhost";
		this.name = getClass().getSimpleName();
	}

	/**
	 * 다음 로그 이벤트를 읽어서 반환합니다.
	 *
	 * @return 새로운 LogEvent 객체, 또는 읽을 데이터가 없으면 null
	 */
	public abstract LogEvent run();

	/**
	 * 새로운 LogEvent를 생성하는 헬퍼 메서드입니다.
	 */
	protected LogEvent createLogEvent(String originalText) {
		return new LogEvent(originalText, sourceHost, messageType);
	}

	protected LogEvent createLogEvent(String originalText, String host) {
		return new LogEvent(originalText, host, messageType);
	}

	@Override
	public String toString() {
		return this.name;
	}
}
