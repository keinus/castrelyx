package org.keinus.logparser.interfaces.dto.transform;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMetadataDto {
    private List<ColumnMetadataDto> commonSchema;
    private Map<String, List<ColumnMetadataDto>> subSchemas; // key: sub-table name (e.g. event_web)
}
