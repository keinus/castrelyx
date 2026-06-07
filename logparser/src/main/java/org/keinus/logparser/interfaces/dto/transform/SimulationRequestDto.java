package org.keinus.logparser.interfaces.dto.transform;

import java.util.Map;

import org.keinus.logparser.domain.model.mapping.MappingConfiguration;

import lombok.Data;

@Data
public class SimulationRequestDto {
    private String messageType;
    private Map<String, Object> sampleData;
    private MappingConfiguration temporaryConfig; // Optional: Test this config instead of stored one
}
