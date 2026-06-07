package org.keinus.logparser.interfaces.dto.response;

import java.time.LocalDateTime;

public record ConfigSettingsDTO(
        Long id,
        String configKey,
        String configValue,
        String dataType,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {}
