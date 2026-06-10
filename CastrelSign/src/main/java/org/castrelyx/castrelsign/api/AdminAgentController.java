package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.castrelyx.castrelsign.persistence.AgentRepository.AgentRecord;
import org.castrelyx.castrelsign.persistence.AgentRepository.CertificateRecord;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentController {
  private final AdminTokenService adminTokenService;
  private final AgentRepository agentRepository;

  public AdminAgentController(AdminTokenService adminTokenService, AgentRepository agentRepository) {
    this.adminTokenService = adminTokenService;
    this.agentRepository = agentRepository;
  }

  @GetMapping("/agents")
  public List<AgentResponse> agents(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return agentRepository.listAgents().stream().map(AdminAgentController::agent).toList();
  }

  @GetMapping("/certificates")
  public List<CertificateResponse> certificates(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return agentRepository.listCertificates().stream().map(AdminAgentController::certificate).toList();
  }

  private static AgentResponse agent(AgentRecord record) {
    return new AgentResponse(
        record.agentId(),
        record.hostname(),
        record.version(),
        record.status(),
        record.firstSeenAt(),
        record.lastSeenAt());
  }

  private static CertificateResponse certificate(CertificateRecord record) {
    return new CertificateResponse(
        record.id(),
        record.agentId(),
        record.serialNumber(),
        record.subjectDn(),
        record.notBefore(),
        record.notAfter(),
        record.status(),
        record.issuedAt());
  }

  public record AgentResponse(
      @JsonProperty("agent_id") String agentId,
      String hostname,
      String version,
      String status,
      @JsonProperty("first_seen_at") String firstSeenAt,
      @JsonProperty("last_seen_at") String lastSeenAt) {
  }

  public record CertificateResponse(
      long id,
      @JsonProperty("agent_id") String agentId,
      @JsonProperty("serial_number") String serialNumber,
      @JsonProperty("subject_dn") String subjectDn,
      @JsonProperty("not_before") String notBefore,
      @JsonProperty("not_after") String notAfter,
      String status,
      @JsonProperty("issued_at") String issuedAt) {
  }
}
