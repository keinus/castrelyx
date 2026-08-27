package org.keinus.logparser.interfaces.dto.request;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class ProcessingStepOrderRequest {
    private List<StepRef> steps = new ArrayList<>();

    @Data
    public static class StepRef {
        private String kind;
        private Long id;
    }
}
