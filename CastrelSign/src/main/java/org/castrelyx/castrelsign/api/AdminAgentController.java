package org.castrelyx.castrelsign.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.castrelyx.castrelsign.persistence.AgentRepository.AuditEventRecord;
import org.castrelyx.castrelsign.persistence.AgentRepository.AgentRecord;
import org.castrelyx.castrelsign.persistence.AgentRepository.CertificateRecord;
import org.castrelyx.castrelsign.security.AdminTokenService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminAgentController {
  private final AdminTokenService adminTokenService;
  private final AgentRepository agentRepository;
  private final CertificateAuthority authority;

  public AdminAgentController(AdminTokenService adminTokenService, AgentRepository agentRepository, CertificateAuthority authority) {
    this.adminTokenService = adminTokenService;
    this.agentRepository = agentRepository;
    this.authority = authority;
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

  @GetMapping("/audit-events")
  public List<AuditEventResponse> auditEvents(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return agentRepository.listAuditEvents().stream().map(AdminAgentController::auditEvent).toList();
  }

  @GetMapping(value = "/ca.pem", produces = MediaType.TEXT_PLAIN_VALUE)
  public String rootCa(@RequestHeader(name = "Authorization", required = false) String authorization) {
    adminTokenService.requireValid(authorization);
    return authority.rootCertificatePem();
  }

  @PostMapping("/agents/{agentId}/block")
  public ResponseEntity<Void> blockAgent(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId) {
    adminTokenService.requireValid(authorization);
    agentRepository.blockAgent(agentId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/agents/{agentId}/reactivate")
  public ResponseEntity<Void> reactivateAgent(@RequestHeader(name = "Authorization", required = false) String authorization,
      @PathVariable String agentId) {
    adminTokenService.requireValid(authorization);
    agentRepository.reactivateAgent(agentId);
    return ResponseEntity.noContent().build();
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

  private static AuditEventResponse auditEvent(AuditEventRecord record) {
    return new AuditEventResponse(
        record.id(),
        record.eventType(),
        record.agentId(),
        record.message(),
        record.createdAt());
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

  public record AuditEventResponse(
      long id,
      @JsonProperty("event_type") String eventType,
      @JsonProperty("agent_id") String agentId,
      String message,
      @JsonProperty("created_at") String createdAt) {
  }
}
