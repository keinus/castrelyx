package org.keinus.logparser.infrastructure.config;

import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ThreadManagerBean.class)
class ThreadManagerBeanTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldCreateThreadManagerBean() {
        ThreadManager threadManager = context.getBean(ThreadManager.class);
        assertThat(threadManager).isNotNull();
    }
}
