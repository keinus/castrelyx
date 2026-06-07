package org.keinus.logparser.domain.model.mapping;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MappingConfiguration {
    // Unique identifier for this configuration (e.g., "syslog-mapping")
    private String id;
    
    // The message type this configuration applies to (e.g., "syslog")
    private String messageType;

    // Mappings for the Common Core fields (event_time, src_ip, etc.)
    private List<FieldMapping> commonMappings = new ArrayList<>();

    // Rules to determine sub-tables and their mappings
    private List<SubTableRule> subTableRules = new ArrayList<>();
}
