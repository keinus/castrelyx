package org.keinus.logparser.interfaces.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record ParserDTO(
        Long id,
        String type,
        String messagetype,
        Map<String, Object> param,
        Integer priority,
        Boolean enabled,
        Boolean continueOnFailure,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {}
