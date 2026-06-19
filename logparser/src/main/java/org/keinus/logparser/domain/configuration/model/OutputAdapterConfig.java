package org.keinus.logparser.domain.configuration.model;

import org.keinus.logparser.domain.configuration.model.ConfigSchema.*;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 출력 어댑터 설정을 위한 타입 안전한 설정 클래스입니다.
 */
@Data
public class OutputAdapterConfig {

    private Long id;

    @Required
    @Choice(values = {
        "ConsoleOutputAdapter",
        "KafkaOutputAdapter",
        "HttpOutputAdapter",
        "TcpOutputAdapter",
        "OpenSearchOutputAdapter",
        "RabbitMQAdapter",
        "MariaDbOutputAdapter",
        "ClickHouseOutputAdapter",
        "BenchmarkAdapter",
        "console",
        "tcp",
        "http",
        "kafka",
        "opensearch",
        "rabbitmq",
        "mariadb",
        "clickhouse",
        "benchmark"
    })
    @Description("출력 어댑터의 타입")
    private String type;

    @Description("처리할 메시지 타입 (`all`이면 모든 타입 처리)")
    private String messagetype;

    @Default("false")
    @Description("원본 텍스트를 출력에 포함할지 여부")
    private Boolean addOriginText;

    // === Network 관련 설정 ===

    @Range(min = 1, max = 65535)
    @AdapterSpecific(adapters = {"TcpOutputAdapter"})
    @Description("TCP 연결 포트")
    private Integer port;

    @AdapterSpecific(adapters = {"TcpOutputAdapter", "HttpOutputAdapter", "RabbitMQAdapter"})
    @Description("대상 호스트")
    private String host;

    // === HTTP 관련 설정 ===
    @Url
    @AdapterSpecific(adapters = {"HttpOutputAdapter, OpenSearchOutputAdapter"})
    @Description("HTTP 엔드포인트 URL")
    private String url;

    @Choice(values = {"POST", "PUT", "PATCH"})
    @Default("POST")
    @AdapterSpecific(adapters = {"HttpOutputAdapter"})
    @Description("HTTP 메서드")
    private String method;

    @AdapterSpecific(adapters = {"HttpOutputAdapter"})
    @Description("HTTP 헤더 (key-value 쌍)")
    private Map<String, String> headers;

    // === Kafka 관련 설정 ===

    @AdapterSpecific(adapters = {"KafkaOutputAdapter"})
    @Description("Kafka 토픽 ID")
    private String topicid;

    @AdapterSpecific(adapters = {"KafkaOutputAdapter"})
    @Description("Kafka 브로커 서버 목록")
    private String bootstrapservers;

    @AdapterSpecific(adapters = {"KafkaOutputAdapter"})
    @Description("Kafka 프로듀서 키")
    private String key;

    // === OpenSearch/Elasticsearch 관련 설정 ===

    @AdapterSpecific(adapters = {"OpenSearchOutputAdapter"})
    @Description("인덱스 이름 (템플릿 지원: %{field}, %{yyMMdd})")
    private String index;

    @AdapterSpecific(adapters = {"OpenSearchOutputAdapter"})
    @Description("인증 사용자명")
    private String osUsername;

    @AdapterSpecific(adapters = {"OpenSearchOutputAdapter"})
    @Description("인증 비밀번호")
    private String osPassword;

    @Choice(values = {"create", "index", "update", "upsert"})
    @Default("index")
    @AdapterSpecific(adapters = {"OpenSearchOutputAdapter"})
    @Description("인덱싱 작업 타입")
    private String action;

    // === RabbitMQ 관련 설정 ===

    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("RabbitMQ 라우팅 키")
    private String routingkey;

    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("RabbitMQ 익스체인지")
    private String exchange;

    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("RabbitMQ 사용자명")
    private String rmqUsername;

    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("RabbitMQ 비밀번호")
    private String rmqPassword;

    @Range(min = 1, max = 65535)
    @Default("5672")
    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("RabbitMQ 포트")
    private Integer rmqPort;

    @AdapterSpecific(adapters = {"RabbitMQAdapter"})
    @Description("태그 기반 필터링 조건 (key: [value1, value2])")
    private Map<String, List<String>> tagpass;

    // === 성능 관련 설정 ===

    @Range(min = 1, max = 10000)
    @Default("100")
    @Description("배치 크기")
    private Integer batchSize;

    @Range(min = 100, max = 60000)
    @Default("5000")
    @Description("배치 플러시 간격 (밀리초)")
    private Integer flushIntervalMs;

    @Range(min = 1, max = 10)
    @Default("3")
    @Description("재시도 횟수")
    private Integer retryCount;

    @Range(min = 100, max = 30000)
    @Default("1000")
    @Description("재시도 간격 (밀리초)")
    private Integer retryDelayMs;

    // === 공통 설정 ===

    @Default("true")
    @Description("어댑터 활성화 여부")
    private Boolean enabled;

    @Range(min = 1000, max = 60000)
    @Default("30000")
    @Description("연결 타임아웃 (밀리초)")
    private Integer timeoutMs;

    @AdapterSpecific(adapters = {"MariaDbOutputAdapter", "ClickHouseOutputAdapter"})
    @Description("Adapter-specific JSON configuration")
    private String configParams;

    /**
     * 어댑터 타입별 필수 필드 검증
     */
    public void validate() {
        // type 필드 필수 검증
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("type is required");
        }

        switch (type) {
            case "HttpOutputAdapter":
            case "http":
                if (url == null || url.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'url' field");
                }
                break;
            case "TcpOutputAdapter":
            case "tcp":
                if (port == null || host == null) {
                    throw new IllegalArgumentException(type + " requires 'port' and 'host' fields");
                }
                break;
            case "KafkaOutputAdapter":
            case "kafka":
                if (topicid == null || bootstrapservers == null) {
                    throw new IllegalArgumentException(type + " requires 'topicid' and 'bootstrapservers' fields");
                }
                break;
            case "OpenSearchOutputAdapter":
            case "opensearch":
                if (url == null || url.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'url' field");
                }
                if (index == null || index.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'index' field");
                }
                break;
            case "RabbitMQAdapter":
            case "rabbitmq":
                if (host == null || routingkey == null || exchange == null) {
                    throw new IllegalArgumentException(type + " requires 'host', 'routingkey', and 'exchange' fields");
                }
                break;
            case "MariaDbOutputAdapter":
            case "mariadb":
                if (configParams == null || configParams.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'configParams' field");
                }
                break;
            case "ClickHouseOutputAdapter":
            case "clickhouse":
                if (configParams == null || configParams.trim().isEmpty()) {
                    throw new IllegalArgumentException(type + " requires 'configParams' field");
                }
                break;
        }
    }
}
