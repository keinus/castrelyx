package org.keinus.logparser.infrastructure.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CustomThreadFactoryTest {

    @Test
    void testNewThread() {
        CustomThreadFactory factory = new CustomThreadFactory("test-pool");
        Thread t = factory.newThread(() -> {});
        
        assertThat(t.getName()).startsWith("test-pool-");
        assertThat(t.isVirtual()).isTrue();
    }
}
