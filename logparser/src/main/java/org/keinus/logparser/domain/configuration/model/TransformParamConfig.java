package org.keinus.logparser.domain.configuration.model;

import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 다양한 변환(transform) 유형에 필요한 파라미터들을 담는 데이터 클래스입니다.
 * <p>
 * 이 클래스는 {@link TransformConfig} 내의 'param' 필드에 해당하며,
 * 각 변환 로직({@link org.keinus.logparser.domain.transformation.model.Filter},
 * {@link org.keinus.logparser.domain.transformation.model.AddProperty},
 * {@link org.keinus.logparser.domain.transformation.model.RemoveProperty})이 필요로 하는
 * 구체적인 설정 값들을 포함합니다.
 *
 * @see TransformConfig
 * @see lombok.Data
 */
@Data
public class TransformParamConfig {
    Map<String, String> pass;
    Map<String, String> drop;
    Map<String, List<String>> add;
    List<String> remove;
}
