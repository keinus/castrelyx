package org.keinus.logparser.infrastructure.config;

import org.keinus.logparser.infrastructure.util.ThreadManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link ThreadManager}를 Spring의 Bean으로 등록하는 설정 클래스입니다.
 * <p>
 * 이 클래스는 {@link Configuration} 어노테이션을 통해 Spring 컨테이너에 의해 관리되며,
 * {@link Bean} 어노테이션이 붙은 {@code threadManager()} 메서드는
 * {@code ThreadManager}의 싱글턴 인스턴스를 생성하여 제공합니다.
 * <p>
 * 생성된 스레드 매니저는 "LogParser"라는 이름 접두사를 사용하여 스레드를 생성하게 되며,
 * 애플리케이션 전반에서 비동기 작업을 관리하는 데 사용됩니다.
 *
 * @see org.keinus.logparser.core.util.ThreadManager
 * @see org.springframework.context.annotation.Bean
 * @see org.springframework.context.annotation.Configuration
 */
@Configuration
public class ThreadManagerBean {
    @Bean
    ThreadManager threadManager() {
        return new ThreadManager("LogParser", 80);
    }
}
