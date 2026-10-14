package org.keinus.logparser.domain.output.model;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import org.keinus.logparser.domain.model.LogEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.Getter;

/**
 * 처리된 메시지를 최종 목적지(sink)로 전송하는 모든 출력 어댑터의 추상 기반 클래스입니다.
 * <p>
 * 이 클래스는 다양한 출력 방식(예: Console, Kafka, OpenSearch)을 표준화된 인터페이스로 추상화합니다.
 * 모든 구체적인 출력 어댑터는 이 클래스를 상속받아 {@link #send(Map, String)} 메서드를 구현해야 합니다.
 * <p>
 * 각 어댑터는 특정 메시지 타입({@code messagetype})에 바인딩될 수 있으며,
 * 최종 출력에 원본 로그 텍스트를 포함할지 여부({@code add_origin_text})를 설정할 수 있습니다.
 *
 * @see java.io.Closeable
 * @see org.keinus.logparser.core.dispatch.OutputAdapterProcedure
 */
public abstract class OutputAdapter implements Closeable {
	private static final int DEFAULT_TIMEOUT_MS = 30_000;
	private static final ObjectMapper OBJECT_MAPPER = createObjectMapper();

	@Getter
	private Long id;
	@Getter
	private String name;

	@Getter
	private String type = "";

	protected final int timeoutMs;

	private volatile java.util.function.Consumer<RuntimeException> backgroundFailureHandler;
	protected volatile boolean backgroundFlushPaused;

	/** Runtime recovery owns failed background batches as well as synchronous sends. */
	public final void setBackgroundFailureHandler(java.util.function.Consumer<RuntimeException> handler) {
		this.backgroundFailureHandler = handler;
	}

	public final void setBackgroundFlushPaused(boolean paused) {
		this.backgroundFlushPaused = paused;
	}

	protected final void reportBackgroundFailure(RuntimeException failure) {
		var handler = backgroundFailureHandler;
		if (handler != null) handler.accept(failure);
	}

	public void retryPendingFlush() {}

	@Getter
	private final boolean addOriginText;

	protected OutputAdapter(Map<String, String> obj) throws IOException {
		if (obj == null) {
			throw new IOException("Property not found.");
		}
		// ID는 OutputFactory에서 설정하거나 Config 객체를 전달받도록 수정해야 함.
		// 현재 구조상 Map을 전달받으므로 Map에서 꺼내야 함.
		if (obj.containsKey("id")) {
			this.id = Long.parseLong(obj.get("id"));
		}
		String messageType = obj.get("messagetype");
		this.type = (messageType == null || messageType.isBlank()) ? "all" : messageType;
		this.name = getClass().getSimpleName() + ":" + obj.toString();
		this.timeoutMs = parseTimeout(obj.get("timeoutMs"));
		this.addOriginText = Boolean.parseBoolean(obj.getOrDefault("add_origin_text", "false"));
	}

	public String getMessageType() {
		return type;
	}

	public final int getTimeoutMs() {
		return timeoutMs;
	}

	public abstract void send(LogEvent logEvent);

	protected String serializeEvent(LogEvent logEvent) {
		try {
			return logEvent.toOutputJson(addOriginText);
		} catch (RuntimeException e) {
			throw deliveryFailure("Failed to serialize output payload", e);
		}
	}

	protected Map<String, Object> outputMap(LogEvent logEvent) {
		try {
			return logEvent.toOutputMap(addOriginText);
		} catch (RuntimeException e) {
			throw deliveryFailure("Failed to create output payload", e);
		}
	}

	protected String toJson(Object value) {
		try {
			return OBJECT_MAPPER.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw deliveryFailure("Failed to serialize output payload", e);
		}
	}

	protected OutputDeliveryException deliveryFailure(String message) {
		return new OutputDeliveryException(getClass().getSimpleName() + ": " + message);
	}

	protected OutputDeliveryException deliveryFailure(String message, Throwable cause) {
		return new OutputDeliveryException(getClass().getSimpleName() + ": " + message, cause);
	}

	private int parseTimeout(String timeoutValue) throws IOException {
		if (timeoutValue == null || timeoutValue.isBlank()) {
			return DEFAULT_TIMEOUT_MS;
		}

		try {
			int parsedTimeout = Integer.parseInt(timeoutValue);
			if (parsedTimeout <= 0) {
				throw new IOException("timeoutMs must be greater than zero");
			}
			return parsedTimeout;
		} catch (NumberFormatException e) {
			throw new IOException("Invalid timeoutMs value: " + timeoutValue, e);
		}
	}

	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		return mapper;
	}

	@Override
	public String toString() {
		return this.name;
	}
}
