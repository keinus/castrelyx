package org.keinus.logparser.infrastructure.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadUtilTest {

    @Test
    void testParseInt() {
        assertThat(ThreadUtil.parseInt("123", 0)).isEqualTo(123);
        assertThat(ThreadUtil.parseInt("abc", 99)).isEqualTo(99);
    }

    @Test
    void testSleep() {
        long start = System.currentTimeMillis();
        ThreadUtil.sleep(100);
        long end = System.currentTimeMillis();
        assertThat(end - start).isGreaterThanOrEqualTo(100);
    }

    @Test
    void testSleepInterrupted() throws InterruptedException {
        Thread t = new Thread(() -> ThreadUtil.sleep(5000));
        t.start();
        Thread.sleep(100);
        t.interrupt();
        t.join(1000);
        assertThat(t.isAlive()).isFalse();
    }
}
