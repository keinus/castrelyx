package org.keinus.logparser.domain.configuration.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 설정 필드의 메타데이터를 정의하는 어노테이션들입니다.
 */
public class ConfigSchema {

    /**
     * 설정 필드가 필수임을 나타냅니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Required {
        String message() default "This field is required";
    }

    /**
     * 설정 필드의 기본값을 지정합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Default {
        String value();
    }

    /**
     * 숫자 필드의 범위를 제한합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Range {
        int min() default Integer.MIN_VALUE;
        int max() default Integer.MAX_VALUE;
    }

    /**
     * 문자열 필드가 선택 가능한 값들을 제한합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Choice {
        String[] values();
    }

    /**
     * 설정 필드의 설명을 제공합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Description {
        String value();
    }

    /**
     * 특정 어댑터 타입에서만 사용되는 필드임을 나타냅니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface AdapterSpecific {
        String[] adapters();
    }

    /**
     * URL 형식의 문자열인지 검증합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Url {
    }

    /**
     * 파일 경로인지 검증합니다.
     */
    @Target(ElementType.FIELD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface FilePath {
        boolean mustExist() default false;
    }
}