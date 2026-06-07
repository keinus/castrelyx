package org.keinus.logparser.domain.input.model;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 테스트용 가짜 로그 데이터를 생성하는 입력 어댑터입니다.
 * <p>
 * {@link InputAdapter}를 구현하며, Suricata EVE JSON 형식의 로그를 생성합니다.
 * <p>
 * 주요 기능:
 * <ul>
 *     <li><b>자동 생성:</b> 설정된 간격(interval)마다 로그 이벤트를 자동 생성합니다.</li>
 *     <li><b>다양한 이벤트 타입:</b> alert, dns 등 다양한 event_type을 무작위로 생성합니다.</li>
 *     <li><b>실시간 타임스탬프:</b> 현재 시간을 기반으로 타임스탬프를 생성합니다.</li>
 * </ul>
 *
 * 설정 예시:
 * <pre>
 * type: fake
 * interval: 1000  # 1초마다 생성 (밀리초)
 * </pre>
 *
 * @see org.keinus.logparser.core.interfaces.InputAdapter
 */
public class FakeInputAdapter extends InputAdapter {
    private static final Logger logger = LoggerFactory.getLogger(FakeInputAdapter.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ").withZone(ZoneOffset.systemDefault());

    private final String hostName;
    private final Random random = new Random();
    private final AtomicLong flowIdGenerator = new AtomicLong(System.currentTimeMillis() * 1000000);

    // 샘플 IP 주소들
    private static final String[] INTERNAL_IPS = {
        "192.168.1.28", "192.168.1.100", "192.168.1.50", "10.0.0.10", "172.16.0.5"
    };

    private static final String[] EXTERNAL_IPS = {
        "8.8.8.8", "8.8.4.4", "1.1.1.1", "208.67.222.222", "172.217.175.46"
    };

    private static final String[] DNS_DOMAINS = {
        "dns.google", "cloudflare-dns.com", "google.com", "facebook.com", "amazon.com"
    };

    private static final String[] ALERT_SIGNATURES = {
        "ET INFO Observed Google DNS over HTTPS Domain (dns .google in TLS SNI)",
        "ET DNS Standard query response, Name Error",
        "ET POLICY Suspicious HTTP Client User-Agent",
        "ET SCAN Potential SSH Scan",
        "ET POLICY Outbound Connection to Suspicious Domain"
    };

    public FakeInputAdapter(InputAdapterConfig config) throws IOException {
        super(config);
        this.hostName = java.net.InetAddress.getLocalHost().getHostName();
        logger.info("Fake Input Adapter initialized. Host: {}.", hostName);
    }

    @Override
    public LogEvent run() {
        String jsonLog = generateLog();

        logger.debug("Generated fake log: {}",
            jsonLog.length() > 100 ? jsonLog.substring(0, 100) + "..." : jsonLog);

        return createLogEvent(jsonLog);
    }

    /**
     * 지정된 이벤트 타입에 대한 JSON 로그를 생성합니다.
     */
    private String generateLog() {
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        long flowId = flowIdGenerator.incrementAndGet();
        return generateAlertLog(timestamp, flowId);
    }

    /**
     * Alert 타입 로그를 생성합니다.
     */
    private String generateAlertLog(String timestamp, long flowId) {
        String srcIp = INTERNAL_IPS[random.nextInt(INTERNAL_IPS.length)];
        String destIp = EXTERNAL_IPS[random.nextInt(EXTERNAL_IPS.length)];
        int srcPort = 50000 + random.nextInt(15000);
        int destPort = random.nextBoolean() ? 443 : 80;
        String signature = ALERT_SIGNATURES[random.nextInt(ALERT_SIGNATURES.length)];
        String sni = DNS_DOMAINS[random.nextInt(DNS_DOMAINS.length)];

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"timestamp\":\"").append(timestamp).append("\",");
        sb.append("\"flow_id\":").append(flowId).append(",");
        sb.append("\"in_iface\":\"igc1\",");
        sb.append("\"event_type\":\"alert\",");
        sb.append("\"src_ip\":\"").append(srcIp).append("\",");
        sb.append("\"src_port\":").append(srcPort).append(",");
        sb.append("\"dest_ip\":\"").append(destIp).append("\",");
        sb.append("\"dest_port\":").append(destPort).append(",");
        sb.append("\"proto\":\"TCP\",");
        sb.append("\"pkt_src\":\"wire/pcap\",");
        sb.append("\"tx_id\":0,");
        sb.append("\"alert\":{");
        sb.append("\"action\":\"allowed\",");
        sb.append("\"gid\":1,");
        sb.append("\"signature_id\":").append(2000000 + random.nextInt(50000)).append(",");
        sb.append("\"rev\":").append(random.nextInt(10) + 1).append(",");
        sb.append("\"signature\":\"").append(signature).append("\",");
        sb.append("\"category\":\"Misc activity\",");
        sb.append("\"severity\":").append(random.nextInt(3) + 1).append(",");
        sb.append("\"source\":{\"ip\":\"").append(destIp).append("\",\"port\":").append(destPort).append("},");
        sb.append("\"target\":{\"ip\":\"").append(srcIp).append("\",\"port\":").append(srcPort).append("}");
        sb.append("},");
        sb.append("\"tls\":{");
        sb.append("\"sni\":\"").append(sni).append("\",");
        sb.append("\"version\":\"UNDETERMINED\",");
        sb.append("\"ja3\":{");
        sb.append("\"hash\":\"4770c4d982ac5d0e1986ab135810b795\",");
        sb.append("\"string\":\"771,4865-4866-4867-49195-49199-49196-49200-52393-52392-49171-49172-156-157-47-53,35-27-10-65037-0-16-13-18-23-11-45-5-51-43-17513-65281,29-23-24,0\"");
        sb.append("}");
        sb.append("},");
        sb.append("\"app_proto\":\"tls\",");
        sb.append("\"direction\":\"to_server\",");
        sb.append("\"flow\":{");
        sb.append("\"pkts_toserver\":").append(random.nextInt(10) + 1).append(",");
        sb.append("\"pkts_toclient\":").append(random.nextInt(10) + 1).append(",");
        sb.append("\"bytes_toserver\":").append(random.nextInt(1000) + 100).append(",");
        sb.append("\"bytes_toclient\":").append(random.nextInt(1000) + 100).append(",");
        sb.append("\"start\":\"").append(timestamp).append("\",");
        sb.append("\"src_ip\":\"").append(srcIp).append("\",");
        sb.append("\"dest_ip\":\"").append(destIp).append("\",");
        sb.append("\"src_port\":").append(srcPort).append(",");
        sb.append("\"dest_port\":").append(destPort);
        sb.append("}");
        sb.append("}");

        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        logger.info("Fake Input Adapter closed.");
    }
}
