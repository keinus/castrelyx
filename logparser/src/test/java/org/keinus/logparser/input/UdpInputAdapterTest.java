package org.keinus.logparser.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.keinus.logparser.domain.input.model.UdpInputAdapter;
import org.keinus.logparser.domain.model.LogEvent;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UdpInputAdapter 클래스의 단위 테스트
 *
 * 테스트 대상 함수들:
 * - UdpInputAdapter(InputAdapterConfig) : 생성자 테스트
 * - close() : 데이터그램 소켓 정리 테스트
 * - getType(), getSourceHost() : 기본 속성 테스트
 */
class UdpInputAdapterTest {

    private InputAdapterConfig validConfig;
    private UdpInputAdapter adapter;
    private int testPort = 19082; // 테스트용 포트

    @BeforeEach
    void setUp() {
        validConfig = new InputAdapterConfig();
        validConfig.setType("UdpInputAdapter");
        validConfig.setPort(testPort);
        validConfig.setMessagetype("udp-message");
        validConfig.setHost("localhost");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
    }

    @Test
    @DisplayName("생성자 테스트 - 유효한 설정으로 생성")
    void testConstructorWithValidConfig() throws IOException {
        // When & Then
        assertDoesNotThrow(() -> {
            adapter = new UdpInputAdapter(validConfig);
        });
    }

    @Test
    @DisplayName("생성자 테스트 - null 설정으로 생성 시 예외 발생")
    void testConstructorWithNullConfig() {
        // When & Then
        assertThrows(IOException.class, () -> new UdpInputAdapter(null));
    }

    @Test
    @DisplayName("생성자 테스트 - 포트 누락 시 예외 발생")
    void testConstructorWithMissingPort() {
        // Given
        validConfig.setPort(null);

        // When & Then
        assertThrows(IllegalArgumentException.class, () -> new UdpInputAdapter(validConfig));
    }

    @Test
    @DisplayName("close() 테스트 - 데이터그램 소켓 정리")
    void testClose() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);

        // When & Then
        assertDoesNotThrow(() -> adapter.close());
    }

    @Test
    @DisplayName("close() 테스트 - 이미 닫힌 어댑터 재시도")
    void testCloseAlreadyClosed() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);
        adapter.close();

        // When & Then
        assertDoesNotThrow(() -> adapter.close());
    }

    @Test
    @DisplayName("getType() 테스트 - 메시지 타입 반환")
    void testGetType() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);

        // When
        String type = adapter.getMessageType();

        // Then
        assertEquals("udp-message", type);
    }

    @Test
    @DisplayName("getSourceHost() 테스트 - 소스 호스트 반환")
    void testGetSourceHost() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);

        // When
        String host = adapter.getSourceHost();

        // Then
        assertEquals("localhost", host);
    }

    @Test
    @DisplayName("run() 테스트 - null 서버소켓으로 null 반환")
    void testRunWithNullServerSocket() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);
        adapter.close(); // 서버소켓을 null로 만들기

        // When
        LogEvent result = adapter.run();

        // Then
        assertNull(result);
    }

    @Test
    @DisplayName("UDP 기본 동작 테스트")
    void testUdpBasicOperation() throws IOException {
        // Given
        adapter = new UdpInputAdapter(validConfig);

        // When & Then
        // run() 메서드가 블로킹이므로 실제 패킷 없이는 테스트하기 어려움
        // 대신 기본 동작들만 테스트
        assertEquals("udp-message", adapter.getMessageType());
        assertEquals("localhost", adapter.getSourceHost());

        // close() 메서드도 테스트
        assertDoesNotThrow(() -> adapter.close());
    }
}
