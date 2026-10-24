package org.castrelyx.manager.hunt.v2;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HuntTimeConfiguration {
  @Bean
  Clock huntClock() {
    return Clock.systemUTC();
  }
}
