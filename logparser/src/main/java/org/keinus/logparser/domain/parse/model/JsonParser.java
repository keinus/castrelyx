package org.keinus.logparser.domain.parse.model;

import java.util.Map;

import org.keinus.logparser.domain.model.LogEvent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonParser implements IParser {
	/**
	 * Parser that converts JSON-formatted log messages into a map of key-value pairs.
	 * Uses Jackson for deserialization.
	 */
    private final ObjectMapper objectMapper;
    private final TypeReference<Map<String, Object>> typeReference = new TypeReference<>() {};

    public JsonParser() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
	public void init(Object param) {
		// 초기화 없음.
	}

	@Override
	public boolean parse(LogEvent logEvent) {
		try {
			Map<String, Object> parsed = objectMapper.readValue(logEvent.getOriginalText(), typeReference);
			if (parsed != null && !parsed.isEmpty()) {
				logEvent.setFields(parsed);
				return true;
			}
		} catch(Exception e) {
			log.error("JSON parsing failed: {}", e.getMessage());
			logEvent.markAsError("JSON parsing failed: " + e.getMessage());
		}
		return false;
	}

}
