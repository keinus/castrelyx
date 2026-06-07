package org.castrelyx.castrelsign.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrollmentTokenResponse(
    long id,
    String name,
    String token,
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("max_uses") int maxUses,
    @JsonProperty("used_count") int usedCount,
    @JsonProperty("expires_at") Instant expiresAt,
    @JsonProperty("revoked_at") Instant revokedAt,
    @JsonProperty("created_at") Instant createdAt,
    @JsonProperty("last_used_at") Instant lastUsedAt,
    @JsonProperty("last_used_agent_id") String lastUsedAgentId) {
}

