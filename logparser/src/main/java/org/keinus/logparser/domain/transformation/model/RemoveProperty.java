package org.keinus.logparser.domain.transformation.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 메시지에서 지정된 속성(필드)들을 제거하는 변환(Transform) 클래스입니다.
 * <p>
 * 이 클래스는 {@link ITransform} 인터페이스를 구현하며, {@link TransformParamConfig}의
 * 'remove' 목록에 정의된 키(key)들을 메시지 맵에서 제거하는 역할을 합니다.
 * 불필요한 필드를 정리하여 데이터 모델을 단순화하거나 민감한 정보를 제거하는 데 사용될 수 있습니다.
 *
 * @see org.keinus.logparser.core.interfaces.ITransform
 * @see org.keinus.logparser.config.TransformParamConfig
 */
public class RemoveProperty implements ITransform {
    private List<String> props = null;

    @Override
	public void init(TransformParamConfig param) {
        this.props = param.getRemove();
        if (this.props == null) {
            this.props = new ArrayList<>();
        }
	}

	@Override
	public boolean transform(LogEvent logEvent) {
		Map<String, Object> fields = logEvent.getFields();

		for(String entry : props) {
			fields.remove(entry);
		}
		return true; // 항상 성공
	}

}
