package org.keinus.logparser.interfaces.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record TransformDTO(
        Long id,
        String type,
        String messagetype,
        Integer priority,
        List<String> filterPass,
        List<String> filterDrop,
        Map<String, Object> addProperties,
        List<String> removeProperties,
        Map<String, Object> configParams,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {}
