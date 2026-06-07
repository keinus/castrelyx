package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateEnrollmentTokenRequest(
    String name,
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("ttl_seconds") Integer ttlSeconds,
    @JsonProperty("max_uses") Integer maxUses) {
}

