package org.keinus.logparser.interfaces.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineTopologyDto {
    private String messageType;
    private String description;
    @Builder.Default
    private List<PipelineStageDto> inputs = new ArrayList<>();
    @Builder.Default
    private List<PipelineStageDto> processing = new ArrayList<>();
    @Builder.Default
    private List<PipelineStageDto> outputs = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineStageDto {
        private Long id;
        private String type;
        private String name;
        private String detail;
        private String badge;
        private boolean enabled;
        private Integer priority;
    }
}
