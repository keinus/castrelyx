package org.keinus.logparser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LogparserApplication
 *
 * 이 클래스는 Spring Boot 기반의 로그 파서 애플리케이션의 진입점 역할을 합니다.
 * 주로 main 메소드가 포함되어 있으며, 애플리케이션의 실행을 시작하는 역할을 담당합니다.
 *
 * @author keinus
 */
@SpringBootApplication
public class LogparserApplication {
    
    /**
     * 애플리케이션의 시작점입니다.
     * 이 main 메서드는 JVM에 의해 호출되며, Spring Boot 애플리케이션이 초기화될 때 실행됩니다.
     *
     * @param args 프로그램 시작 시 전달된 명령행 인자들
     */
    public static void main(String[] args) {
        SpringApplication.run(LogparserApplication.class, args);
    }

}