package org.keinus.logparser.interfaces.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record OutputAdapterDTO(
        Long id,
        String type,
        String messagetype,
        String host,
        Integer port,
        String url,
        String method,
        Map<String, String> headers,
        String topicid,
        String bootstrapservers,
        String key,
        String indexTemplate,
        String osUsername,
        String osPassword,
        String action,
        String routingkey,
        String exchange,
        String rmqUsername,
        String rmqPassword,
        Integer rmqPort,
        Map<String, String> tagpass,
        Integer batchSize,
        Integer flushIntervalMs,
        Integer retryCount,
        Integer retryDelayMs,
        Boolean addOriginText,
        Boolean enabled,
        Integer timeoutMs,
        String configParams,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer version
) {}
