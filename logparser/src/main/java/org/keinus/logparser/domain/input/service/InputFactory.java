package org.keinus.logparser.domain.input.service;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Map;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.InputAdapter;

import lombok.extern.slf4j.Slf4j;

/**
 * 설정에 따라 적절한 {@link InputAdapter} 구현체를 동적으로 생성하는 팩토리 클래스입니다.
 * <p>
 * 이 클래스는 'type' 문자열(예: "FileInputAdapter", "TcpInputAdapter")을 기반으로
 * 리플렉션을 사용하여 해당 클래스의 인스턴스를 생성합니다. 이를 통해 코드를 변경하지 않고도
 * 설정 파일 수정을 통해 새로운 입력 어댑터를 추가하거나 변경할 수 있습니다.
 * <p>
 * 모든 {@code InputAdapter} 구현체는 {@code org.keinus.logparser.domain.ingestion.model} 패키지 내에 위치해야 하며,
 * {@code InputAdapterConfig}를 인자로 받는 생성자를 가져야 합니다.
 *
 * @see org.keinus.logparser.domain.input.model.InputAdapter
 * @see org.keinus.logparser.domain.configuration.model.InputAdapterConfig
 */
@Slf4j
public class InputFactory {
	private static final Map<String, String> TYPE_ALIASES = Map.ofEntries(
		Map.entry("file", "FileInputAdapter"),
		Map.entry("tcp", "TcpInputAdapter"),
		Map.entry("udp", "UdpInputAdapter"),
		Map.entry("http", "HttpInputAdapter"),
		Map.entry("https", "HttpsInputAdapter"),
		Map.entry("kafka", "KafkaInputAdapter"),
		Map.entry("snmp", "SnmpInputAdapter"),
		Map.entry("rabbitmq", "RabbitMqInputAdapter"),
		Map.entry("tls_tcp", "TlsTcpInputAdapter"),
		Map.entry("tlstcp", "TlsTcpInputAdapter"),
		Map.entry("tls_rabbitmq", "TlsRabbitMqInputAdapter"),
		Map.entry("tlsrabbitmq", "TlsRabbitMqInputAdapter"),
		Map.entry("tcp_mtls_gzip", "TcpMtlsGzipInputAdapter"),
		Map.entry("fake", "FakeInputAdapter")
	);

	private InputFactory() {
		throw new IllegalStateException("Utility class");
	}

	/**
	 * 타입 안전한 설정 객체를 기반으로 InputAdapter 인스턴스를 생성합니다.
	 *
	 * @param config 입력 어댑터 설정
	 * @return 생성된 InputAdapter 인스턴스
	 * @throws IllegalStateException 어댑터 생성 실패 시
	 */
	public static InputAdapter getInputAdapter(InputAdapterConfig config) {
		String type = normalizeType(config.getType());
		try {
			Class<?> cls = Class.forName("org.keinus.logparser.domain.input.model." + type);
			return (InputAdapter) cls.getDeclaredConstructor(InputAdapterConfig.class).newInstance(config);
		}
		catch (InstantiationException | IllegalAccessException | IllegalArgumentException
			| InvocationTargetException | NoSuchMethodException
			| SecurityException | ClassNotFoundException e) {
			log.error("Invalid Input Adapter. {}", e.getMessage());
			throw new IllegalStateException("Invalid Input Adapter.");
		}
	}

	private static String normalizeType(String type) {
		if (type == null) {
			return "";
		}
		String trimmed = type.trim();
		return TYPE_ALIASES.getOrDefault(trimmed.toLowerCase(Locale.ROOT), trimmed);
	}
}
