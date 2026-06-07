package org.keinus.logparser.domain.transformation.service;

import java.util.Map;

import org.keinus.logparser.domain.model.structured.StructuredEvent;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class StructuredEventSerializer {

    private final ObjectMapper objectMapper;

    public StructuredEventSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> toMap(StructuredEvent event) {
        return objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {});
    }
}
