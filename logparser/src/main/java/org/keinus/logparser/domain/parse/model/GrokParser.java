package org.keinus.logparser.domain.parse.model;

import java.util.Map;

import org.keinus.logparser.domain.model.LogEvent;

import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.Match;


/**
 * Grok 패턴을 사용하여 비정형 텍스트 로그를 구조화된 데이터로 파싱하는 파서입니다.
 * <p>
 * 이 클래스는 {@link IParser} 인터페이스를 구현하며, {@code io.krakens.grok:grok-api} 라이브러리를
 * 사용하여 로그 메시지를 파싱합니다. {@link #init(Object)} 단계에서 주어진 Grok 표현식을
 * 컴파일하고, {@link #parse(LogEvent)} 메서드에서 컴파일된 패턴을 사용하여 메시지를 매칭하고
 * 명명된 필드를 추출합니다.
 * <p>
 * Syslog, Apache 로그 등 널리 알려진 형식의 로그를 파싱하는 데 매우 효과적입니다.
 *
 * @see org.keinus.logparser.core.interfaces.IParser
 * @see io.krakens.grok.api.Grok
 */
public class GrokParser implements IParser {
    private GrokCompiler grokCompiler = GrokCompiler.newInstance();
	Grok grok = null;
	
	public GrokParser() {
		grokCompiler.registerDefaultPatterns();
	}
	
	public void init(Object param) {
		String reg = (String)param;
		grok = grokCompiler.compile(reg);
	}

	@Override
	public boolean parse(LogEvent logEvent) {
		try {
			String message = logEvent.getOriginalText();
			if (message == null || message.equals("")) {
				return false;
			}

			Match gm = grok.match(message);
			if (gm.isNull().booleanValue()) {
				return false;
			}

			Map<String, Object> parsedFields = gm.capture();
			if (parsedFields != null && !parsedFields.isEmpty()) {
				logEvent.setFields(parsedFields);
				return true;
			}
		} catch (Exception e) {
			logEvent.markAsError("Grok parsing failed: " + e.getMessage());
		}
		return false;
	}

}
