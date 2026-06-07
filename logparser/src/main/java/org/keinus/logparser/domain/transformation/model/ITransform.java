package org.keinus.logparser.domain.transformation.model;

import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 로그 이벤트에 대해 데이터 변환을 수행하는 로직의 공통 인터페이스입니다.
 * <p>
 * 모든 변환 구현체는 이 인터페이스를 구현해야 합니다.
 * <ul>
 *     <li>{@link #init(TransformParamConfig)}: 변환에 필요한 파라미터를 초기화합니다.</li>
 *     <li>{@link #transform(LogEvent)}: LogEvent를 변환하거나 필터링합니다.</li>
 * </ul>
 *
 * @see org.keinus.logparser.core.dispatch.TransformService
 * @see org.keinus.logparser.config.TransformParamConfig
 */
public interface ITransform {
    /**
     * 변환 파라미터를 초기화합니다.
     */
    void init(TransformParamConfig param);

    /**
     * LogEvent를 변환합니다.
     *
     * @param logEvent 변환할 로그 이벤트
     * @return 변환 성공 여부 (false면 메시지가 필터링됨)
     */
    boolean transform(LogEvent logEvent);
}
