package org.keinus.logparser.interfaces.dto.transform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMetadataDto {
    private String name;
    private String type;
    private String description;
    private boolean deprecated;
}
