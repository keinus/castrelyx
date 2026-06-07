package org.keinus.logparser.infrastructure.util;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringContextUtilTest {

    @Test
    void testGetBean() {
        ApplicationContext mockContext = mock(ApplicationContext.class);
        String mockBean = "mockBean";
        when(mockContext.getBean(String.class)).thenReturn(mockBean);
        when(mockContext.getBean("beanName")).thenReturn(mockBean);

        SpringContextUtil util = new SpringContextUtil();
        util.setApplicationContext(mockContext);

        assertThat(SpringContextUtil.getBean(String.class)).isEqualTo(mockBean);
        assertThat(SpringContextUtil.getBean("beanName")).isEqualTo(mockBean);
    }
}
