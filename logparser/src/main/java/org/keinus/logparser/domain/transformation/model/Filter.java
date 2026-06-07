package org.keinus.logparser.domain.transformation.model;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.keinus.logparser.domain.configuration.model.TransformParamConfig;
import org.keinus.logparser.domain.model.LogEvent;

/**
 * 메시지의 특정 필드 값을 기준으로 메시지를 필터링하는 변환(Transform) 클래스입니다.
 * <p>
 * 이 클래스는 {@link ITransform} 인터페이스를 구현하며, 두 가지 조건으로 필터링을 수행합니다:
 * <ul>
 *     <li><b>drop:</b> 지정된 필드의 값이 'drop' 목록에 포함된 경우, 해당 메시지를 파이프라인에서 제거합니다.
 *         (빈 맵을 반환하여 제거 신호를 보냅니다.)</li>
 *     <li><b>pass:</b> 'pass' 목록이 설정된 경우, 지정된 필드의 값이 'pass' 목록에 포함되어야만
 *         메시지가 파이프라인을 계속 진행할 수 있습니다. 그렇지 않으면 제거됩니다.</li>
 * </ul>
 * 필터링 규칙은 {@link TransformParamConfig}를 통해 초기화됩니다.
 *
 * @see org.keinus.logparser.core.interfaces.ITransform
 * @see org.keinus.logparser.config.TransformParamConfig
 */
public class Filter implements ITransform {
    private Map<String, Set<String>> pass = new HashMap<>();
    private Map<String, Set<String>> drop = new HashMap<>();

    private void parseParam(Map<String, Set<String>> paramMap, Map<String, String> param) {
		if(param != null) {
			param.forEach((key, value) -> {
				Set<String> values = new HashSet<>();
                if (value != null) {
                    for (String v : value.split(",")) {
                        String trimmed = v.trim();
                        if (!trimmed.isEmpty()) {
                            values.add(trimmed);
                        }
                    }
                }
                paramMap.put(key, values);
			});
		}
    }

	@Override
	public void init(TransformParamConfig param) {
		// 기존 파라미터 구조: Map<String, List<String>>
		// 이를 내부적으로 Map<String, Set<String>>으로 변환하여 저장
        parseParam(this.pass, param.getPass());
        parseParam(this.drop, param.getDrop());
	}

	@Override
	public boolean transform(LogEvent logEvent) {
		Map<String, Object> fields = logEvent.getFields();

		// Drop 조건 검사 (O(1) Lookup)
		for(Entry<String, Set<String>> entry : drop.entrySet()) {
			String prop = entry.getKey();
			Object targetPropObj = fields.get(prop);
			if (targetPropObj == null) continue;
			String targetProp = targetPropObj.toString();
			if(entry.getValue().contains(targetProp))
				return false; // 필터링됨
		}

		// Pass 조건 검사 (O(1) Lookup)
		for(Entry<String, Set<String>> entry : pass.entrySet()) {
			String prop = entry.getKey();
			Object targetPropObj = fields.get(prop);
			if (targetPropObj == null) return false; // null이면 pass 실패
			String targetProp = targetPropObj.toString();
			if(!entry.getValue().contains(targetProp))
				return false; // 필터링됨
		}

		return true; // 통과
	}

}