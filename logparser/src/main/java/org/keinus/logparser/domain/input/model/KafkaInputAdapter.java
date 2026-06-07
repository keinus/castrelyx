package org.keinus.logparser.domain.input.model;

import java.io.IOException;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Apache Kafka 토픽으로부터 메시지를 수신하는 입력 어댑터입니다.
 * <p>
 * 이 클래스는 {@link KafkaConsumer}를 사용하여 지정된 토픽의 메시지를 폴링(poll)하고,
 * 내부 큐를 통해 파이프라인에 전달합니다.
 * <p>
 * 주요 설정:
 * <ul>
 *     <li>{@code bootstrapservers}: 접속할 Kafka 브로커의 주소 목록</li>
 *     <li>{@code topicid}: 구독할 토픽의 이름</li>
 * </ul>
 * 각 인스턴스는 고유한 컨슈머 그룹 ID({@link java.util.UUID})를 사용하여,
 * 여러 인스턴스가 실행되더라도 동일한 토픽의 메시지를 모두 수신할 수 있도록 합니다.
 * <p>
 * <b>주의:</b> 현재 구현에서 {@code messageQueue}가 정적(static)으로 선언되어 있어,
 * 동일한 JVM 내에서 이 어댑터의 여러 인스턴스가 생성될 경우 큐를 공유하게 됩니다.
 * 이는 의도치 않은 동작을 유발할 수 있으므로, 향후 리팩토링 시 인스턴스 변수로
 * 변경하는 것을 고려해야 합니다.
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 * @see org.apache.kafka.clients.consumer.KafkaConsumer
 */
public class KafkaInputAdapter extends InputAdapter {
	private static final Logger LOGGER = LoggerFactory.getLogger(KafkaInputAdapter.class);

	private final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
	private KafkaConsumer<String, String> consumer = null;
	private String host = null;

	public KafkaInputAdapter(InputAdapterConfig config) throws IOException {
		super(config);

		if (config.getBootstrapservers() == null || config.getTopicid() == null) {
			throw new IllegalArgumentException("KafkaInputAdapter requires 'bootstrapservers' and 'topicid'");
		}

		String server = config.getBootstrapservers();
		String topic = config.getTopicid();
		String groupId = config.getGroupId() != null ? config.getGroupId() : UUID.randomUUID().toString();

		Properties consumerProperties = new Properties();
		consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, server);
		consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);

		consumer = new KafkaConsumer<>(consumerProperties);
		consumer.subscribe(java.util.Collections.singletonList(topic));

		this.host = server;

		LOGGER.info("Kafka Input Adapter connected at {} and subscribed to topic {}", server, topic);
	}

	@Override
	public LogEvent run() {
		if (messageQueue.isEmpty()) {
			ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
			if (!records.isEmpty()) {
				for (ConsumerRecord<String, String> item : records) {
					try {
						messageQueue.put(item.value());
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}

		if (!messageQueue.isEmpty()) {
			try {
				return createLogEvent(messageQueue.take(), this.host);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		return null;
	}

	@Override
	public void close() throws IOException {
		messageQueue.clear();
		if (consumer != null) {
			try {
				consumer.close(Duration.ofSeconds(5));
				LOGGER.info("Kafka consumer closed successfully");
			} catch (Exception e) {
				LOGGER.error("Error closing Kafka consumer: {}", e.getMessage(), e);
			} finally {
				consumer = null;
			}
		}
	}
}
