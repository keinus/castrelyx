package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

public record EnrollmentRequest(
    @JsonProperty("agent_id") @NotBlank String agentId,
    String hostname,
    String version,
    @JsonProperty("csr_pem") @NotBlank String csrPem) {
}
