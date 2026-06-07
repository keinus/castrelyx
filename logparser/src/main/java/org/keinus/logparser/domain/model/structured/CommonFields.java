package org.keinus.logparser.domain.model.structured;

import java.time.Instant;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CommonFields {
    // Mandatory
    private Instant eventTime;
    private Instant ingestTime;

    private String eventCategory;
    private String eventType;
    
    // Optional / Normalized
    private String eventAction;
    private String eventResult; // success, failure, unknown

    private Integer severity;

    // Network (Core)
    private String srcIp;
    private Integer srcPort;
    private String dstIp;
    private Integer dstPort;
    private String protocol;

    // Host (Core)
    private String srcHost;
    private String dstHost;

    // User
    private String userName;
    private String userId;

    // Meta
    private String logSource;
    private String rawLog;
}
