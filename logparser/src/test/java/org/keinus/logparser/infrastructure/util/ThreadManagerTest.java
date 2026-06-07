package org.keinus.logparser.infrastructure.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadManagerTest {

    private ThreadManager threadManager;

    @BeforeEach
    void setUp() {
        threadManager = new ThreadManager("test-manager");
    }

    @AfterEach
    void tearDown() {
        threadManager.shutdownAllThreads();
    }

    @Test
    void testExecuteWithName() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        threadManager.executeWithName("task1", () -> {
            try {
                latch.await();
            } catch (InterruptedException e) {
                // ignore
            }
        });

        assertThat(threadManager.getActiveThreads()).contains("task1");
        latch.countDown();
    }

    @Test
    void testExecuteWithDuplicateName() {
        threadManager.executeWithName("task1", () -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        });

        assertThatThrownBy(() -> threadManager.executeWithName("task1", () -> {}))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testStopThread() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        threadManager.executeWithName("task1", () -> {
            started.countDown();
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                // expected
            }
        });

        started.await();
        threadManager.stopThread("task1");
        assertThat(threadManager.getActiveThreads()).doesNotContain("task1");
    }

    @Test
    void testStopThreadsStartingWith() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(2);
        threadManager.executeWithName("prefix-1", () -> {
            started.countDown();
            try { Thread.sleep(10000); } catch (InterruptedException e) {}
        });
        threadManager.executeWithName("prefix-2", () -> {
            started.countDown();
            try { Thread.sleep(10000); } catch (InterruptedException e) {}
        });

        started.await();
        int stopped = threadManager.stopThreadsStartingWith("prefix-");
        assertThat(stopped).isEqualTo(2);
        assertThat(threadManager.getActiveThreads()).isEmpty();
    }

    @Test
    void testGetAllThreadInfo() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        threadManager.executeWithName("task1", () -> {
            started.countDown();
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        List<ThreadManager.ThreadInfo> info = threadManager.getAllThreadInfo();
        assertThat(info).isNotEmpty();
        latch.countDown();
    }
}
