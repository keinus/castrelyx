package org.keinus.logparser.domain.transformation.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 기존 속성들을 그룹화하여 새로운 복합 속성(nested object)을 메시지에 추가하는 변환(Transform) 클래스입니다.
 * <p>
 * 이 클래스는 {@link ITransform} 인터페이스를 구현하며, {@link TransformParamConfig}의
 * 'add' 설정에 따라 동작합니다. 'add' 맵의 키는 새로 생성될 부모 속성의 이름이 되고,
 * 값(리스트)은 해당 부모 속성 아래로 이동할 기존 속성들의 이름 목록이 됩니다.
 * <p>
 * 예를 들어, 'add' 설정이 `{"user": ["name", "email"]}` 이라면,
 * 기존의 `name`과 `email` 필드를 `user`라는 새로운 객체 필드 아래로 묶습니다.
 *
 * @see org.keinus.logparser.core.interfaces.ITransform
 * @see org.keinus.logparser.config.TransformParamConfig
 */
public class AddProperty implements ITransform {
    private Map<String, List<String>> props = null;

    @Override
	public void init(TransformParamConfig param) {
        this.props = param.getAdd();
        if (this.props == null) {
            this.props = new HashMap<>();
        }
	}

	@Override
	public boolean transform(LogEvent logEvent) {
		Map<String, Object> fields = logEvent.getFields();

		for(Entry<String, List<String>> entry : props.entrySet()) {
			String prop = entry.getKey();
			List<String> values = entry.getValue();
            Map<String, Object> target = new HashMap<>();
            fields.put(prop, target);

            for(String attr_name : values) {
                target.put(attr_name, fields.get(attr_name));
                fields.remove(attr_name);  // 원본 필드 제거
            }
		}
		return true; // 항상 성공
	}

}
