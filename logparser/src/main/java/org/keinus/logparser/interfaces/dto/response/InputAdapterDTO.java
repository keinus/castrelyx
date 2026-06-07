package org.keinus.logparser.interfaces.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record InputAdapterDTO(
        Long id,
        String type,
        String messagetype,
        String host,
        Integer port,
        String path,
        String topicid,
        String bootstrapservers,
        String groupId,
        String codec,
        String pathPattern,
        Integer bufferSize,
        Integer timeoutMs,
        Boolean enabled,
        Integer workerThreads,
        Integer queueSize,
        Map<String, Object> configParams,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {}
