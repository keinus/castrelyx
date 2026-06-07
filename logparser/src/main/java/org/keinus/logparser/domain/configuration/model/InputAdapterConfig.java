package org.keinus.logparser.domain.configuration.model;

import org.keinus.logparser.domain.configuration.model.ConfigSchema.*;
import lombok.Data;

/**
 * 입력 어댑터 설정을 위한 타입 안전한 설정 클래스입니다.
 * 각 필드는 메타데이터 어노테이션으로 검증 규칙과 설명을 포함합니다.
 */
@Data
public class InputAdapterConfig {

    private Long id;

    @Required
    @Choice(values = {
        "FileInputAdapter",
        "TcpInputAdapter",
        "UdpInputAdapter",
        "HttpInputAdapter",
        "KafkaInputAdapter",
        "SnmpInputAdapter",
        "RabbitMqInputAdapter",
        "TcpMtlsGzipInputAdapter",
        "FakeInputAdapter"
    })
    @Description("입력 어댑터의 타입")
    private String type;

    @Required
    @Description("메시지 타입 식별자 (파서 매핑에 사용)")
    private String messagetype;

    // === Network 관련 설정 ===

    @Range(min = 1, max = 65535)
    @AdapterSpecific(adapters = {"TcpInputAdapter", "UdpInputAdapter", "HttpInputAdapter", "RabbitMqInputAdapter", "TcpMtlsGzipInputAdapter"})
    @Description("네트워크 포트 번호")
    private Integer port;

    @Default("0.0.0.0")
    @AdapterSpecific(adapters = {"TcpInputAdapter", "UdpInputAdapter", "HttpInputAdapter", "RabbitMqInputAdapter", "TcpMtlsGzipInputAdapter"})
    @Description("바인딩할 호스트 주소")
    private String host;

    // === File 관련 설정 ===

    @FilePath
    @AdapterSpecific(adapters = {"FileInputAdapter"})
    @Description("읽어올 파일의 경로")
    private String path;

    @Default("false")
    @AdapterSpecific(adapters = {"FileInputAdapter"})
    @Description("파일을 처음부터 읽을지 여부 (false면 끝에서부터)")
    private Boolean isFromBeginning;

    // === Kafka 관련 설정 ===

    @AdapterSpecific(adapters = {"KafkaInputAdapter"})
    @Description("Kafka 토픽 ID")
    private String topicid;

    @AdapterSpecific(adapters = {"KafkaInputAdapter"})
    @Description("Kafka 브로커 서버 목록 (예: localhost:9092)")
    private String bootstrapservers;

    @AdapterSpecific(adapters = {"KafkaInputAdapter"})
    @Description("Kafka 컨슈머 그룹 ID")
    private String groupId;

    // === HTTP 관련 설정 ===

    @Choice(values = {"json", "plain", "multipart"})
    @Default("plain")
    @AdapterSpecific(adapters = {"HttpInputAdapter"})
    @Description("HTTP 요청 본문의 인코딩 방식")
    private String codec;

    @Default("/")
    @AdapterSpecific(adapters = {"HttpInputAdapter"})
    @Description("HTTP 엔드포인트 경로")
    private String path_pattern;

    // === 공통 설정 ===

    @Range(min = 1024, max = 1048576)
    @Default("8192")
    @Description("내부 버퍼 크기 (바이트)")
    private Integer bufferSize;

    @Range(min = 100, max = 60000)
    @Default("5000")
    @Description("연결 타임아웃 (밀리초)")
    private Integer timeoutMs;

    @Default("true")
    @Description("어댑터 활성화 여부")
    private Boolean enabled;

    // === 고급 설정 ===

    @Range(min = 1, max = 100)
    @Default("1")
    @Description("동시 처리 스레드 수")
    private Integer workerThreads;

    @Range(min = 100, max = 100000)
    @Default("1000")
    @Description("내부 큐 최대 크기")
    private Integer queueSize;

    @AdapterSpecific(adapters = {"SnmpInputAdapter", "RabbitMqInputAdapter", "TcpMtlsGzipInputAdapter"})
    @Description("Adapter-specific JSON configuration")
    private String configParams;

    /**
     * 어댑터 타입별 필수 필드 검증
     */
    public void validate() {
        switch (type) {
            case "FileInputAdapter":
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("FileInputAdapter requires 'path' field");
                }
                break;
            case "TcpInputAdapter":
            case "UdpInputAdapter":
            case "HttpInputAdapter":
                if (port == null) {
                    throw new IllegalArgumentException(type + " requires 'port' field");
                }
                break;
            case "KafkaInputAdapter":
                if (topicid == null || bootstrapservers == null) {
                    throw new IllegalArgumentException("KafkaInputAdapter requires 'topicid' and 'bootstrapservers' fields");
                }
                break;
            case "SnmpInputAdapter":
                if (configParams == null || configParams.trim().isEmpty()) {
                    throw new IllegalArgumentException("SnmpInputAdapter requires 'configParams' field");
                }
                break;
            case "RabbitMqInputAdapter":
                if (configParams == null || configParams.trim().isEmpty()) {
                    throw new IllegalArgumentException("RabbitMqInputAdapter requires 'configParams' field");
                }
                break;
            case "TcpMtlsGzipInputAdapter":
                if (port == null) {
                    throw new IllegalArgumentException("TcpMtlsGzipInputAdapter requires 'port' field");
                }
                if (configParams == null || configParams.trim().isEmpty()) {
                    throw new IllegalArgumentException("TcpMtlsGzipInputAdapter requires 'configParams' field");
                }
                break;
            case "FakeInputAdapter":
                break;
        }
    }
}
