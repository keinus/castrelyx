package org.keinus.logparser.domain.parse.model;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.keinus.logparser.domain.model.LogEvent;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HttpParser implements IParser {
	/**
	 * Parser for HTTP log messages. Extracts headers and body from HTTP-formatted strings.
	 * Returns a map containing header fields and the message body.
	 */
	@Override
	public void init(Object param) {
		// 초기화 없음.
	}

	@Override
	public boolean parse(LogEvent logEvent) {
		try {
			String message = logEvent.getOriginalText();
			Map<String, Object> headers = new HashMap<>();
			Map<String, Object> retval = new HashMap<>();
			StringBuilder sb = new StringBuilder();

			try(BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8 ))))) {
				String line;
				line = br.readLine();

				while((line=br.readLine()) != null) {
					if(line.equals(""))
						break;
					if(line.contains(":")) {
						String[] split = line.split(":", 2); // 최대 2개로 분할하여 값에 콜론이 있어도 처리
						if (split.length >= 2) {
							headers.put(split[0].toUpperCase().trim(), split[1].trim());
						}
					}
				}

				while((line=br.readLine()) != null) {
					sb.append(line);
					sb.append(System.getProperty("line.separator"));
				}
			} catch (IOException e) {
				log.error(e.getMessage());
				logEvent.markAsError("HTTP parsing IO error: " + e.getMessage());
				return false;
			}
			retval.put("headers", headers);
			retval.put("body", sb.toString());

			if (!retval.isEmpty()) {
				logEvent.setFields(retval);
				return true;
			}
		} catch (Exception e) {
			log.error("HTTP parsing failed: {}", e.getMessage());
			logEvent.markAsError("HTTP parsing failed: " + e.getMessage());
		}
		return false;
	}

}
