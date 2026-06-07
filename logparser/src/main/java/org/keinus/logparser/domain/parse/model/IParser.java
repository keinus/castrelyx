package org.keinus.logparser.domain.parse.model;

import org.keinus.logparser.domain.model.LogEvent;

/**
 * 로그 이벤트를 구조화된 데이터로 변환하는 파서의 공통 인터페이스입니다.
 * <p>
 * 모든 파서 구현체는 이 인터페이스를 구현해야 합니다.
 * <ul>
 *     <li>{@link #init(Object)}: 파서가 필요로 하는 설정(예: Grok 패턴, 정규식)을 초기화합니다.</li>
 *     <li>{@link #parse(LogEvent)}: LogEvent를 파싱하여 구조화된 필드를 추가합니다.</li>
 * </ul>
 *
 * @see org.keinus.logparser.core.dispatch.ParseService
 */
public interface IParser {
    /**
     * 파서를 초기화합니다.
     */
    void init(Object param);

    /**
     * LogEvent를 파싱하여 구조화된 필드를 추가합니다.
     *
     * @param logEvent 파싱할 로그 이벤트
     * @return 파싱 성공 여부
     */
    boolean parse(LogEvent logEvent);
}
