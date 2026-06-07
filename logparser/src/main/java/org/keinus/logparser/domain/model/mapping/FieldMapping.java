package org.keinus.logparser.domain.model.mapping;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldMapping {
    private String sourceField; // Field name in the parsed Map
    private String targetField; // Field name in the StructuredEvent (common or sub)
    private String defaultValue; // Optional default value
}
