package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnrollmentResponse(
    @JsonProperty("agent_id") String agentId,
    @JsonProperty("ca_cert_pem") String caCertPem,
    @JsonProperty("client_cert_pem") String clientCertPem,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("ingest_url") String ingestUrl) {
}
