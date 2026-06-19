package org.keinus.logparser.domain.model.mapping;

import java.time.Instant;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class MappingTemplate {
    private String id;
    private String name;
    private String description;
    private String sourceMessageType;
    private MappingConfiguration config;
    private Instant createdAt;
    private Instant updatedAt;
}
