package org.keinus.logparser.interfaces.dto.response;

import org.keinus.logparser.application.pipeline.OutputAdapterComponent;

/**
 * 런타임 출력 어댑터 전송 메트릭 DTO입니다.
 */
public record OutputAdapterMetricsDto(
        Long adapterId,
        String adapterName,
        String messageType,
        long sentCount,
        long failedCount,
        String lastError,
        Long lastSuccessAt,
        Long lastFailureAt,
        Long lastLatencyMs,
        Double averageLatencyMs
) {
    public static OutputAdapterMetricsDto from(OutputAdapterComponent.AdapterMetricsSnapshot snapshot) {
        return new OutputAdapterMetricsDto(
                snapshot.adapterId(),
                snapshot.adapterName(),
                snapshot.messageType(),
                snapshot.sentCount(),
                snapshot.failedCount(),
                snapshot.lastError(),
                snapshot.lastSuccessAt(),
                snapshot.lastFailureAt(),
                snapshot.lastLatencyMs(),
                snapshot.averageLatencyMs()
        );
    }
}
