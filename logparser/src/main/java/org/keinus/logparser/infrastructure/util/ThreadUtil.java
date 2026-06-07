package org.keinus.logparser.infrastructure.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ThreadUtil {
    private ThreadUtil() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 문자열로 된 숫자를 정수로 변환합니다.
     *
     * @param number 변환할 문자열
     * @return 변환된 정수. 변환이 성공하면 그 값, 실패하면 -1을 반환합니다.
     */
    public static int parseInt(String number, int defaultValue) {
        try {
            return Integer.parseInt(number);
        } catch(NumberFormatException e) {
            return defaultValue;
        }
    }
      
    /**
     * 지정된 밀리초 동안 현재 스레드를 일시 중단합니다.
     *
     * <p>이 메서드는 {@link Thread#sleep(long)}을 호출하여 현재 실행 중인 스레드가 주어진 시간만큼 대기하게 합니다.
     * 이 방법은 다른 작업을 수행하지 않고 자원 사용을 줄이거나 다른 스레드의 동작에 특정 시간 간격으로 반응할 필요가 있는 경우 유용합니다.
     *
     * <p>만약 호출 중 {@code InterruptedException}이 발생하면:
     * - DEBUG 레벨로 로깅 (정상적인 백프레셔나 shutdown 상황)
     * - interrupt 플래그를 다시 설정하지 않음 (스레드가 계속 실행되도록)
     *
     * @param millis 대기 시간(밀리초 단위)
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // interrupt는 백프레셔나 정상적인 제어 흐름의 일부일 수 있으므로
            // DEBUG 레벨로 로깅하고 interrupt 플래그를 다시 설정하지 않습니다.
            // 이를 통해 스레드가 계속 실행될 수 있습니다.
            log.debug("Sleep interrupted (this is normal during backpressure or shutdown): {}", e.getMessage());
        }
    }
}
