package org.keinus.logparser.domain.model.mapping;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SubTableRule {
    private String targetSubTable; // e.g., "event_web"
    private String conditionExpression; // e.g., "dst_port == 80 || protocol == 'HTTP'"
    private List<FieldMapping> mappings = new ArrayList<>();
}
