package org.castrelyx.castrelsign.api;

import java.io.IOException;

import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.castrelyx.castrelsign.security.ClientCertificateService;
import org.castrelyx.castrelsign.security.EnrollmentTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
  private final EnrollmentTokenService tokenService;
  private final ClientCertificateService certificateService;
  private final AgentCertificateService certificateIssuer;
  private final IngestService ingestService;
  private final AgentRepository agentRepository;

  public AgentController(EnrollmentTokenService tokenService, ClientCertificateService certificateService,
      AgentCertificateService certificateIssuer, IngestService ingestService, AgentRepository agentRepository) {
    this.tokenService = tokenService;
    this.certificateService = certificateService;
    this.certificateIssuer = certificateIssuer;
    this.ingestService = ingestService;
    this.agentRepository = agentRepository;
  }

  @PostMapping("/enroll")
  public EnrollmentResponse enroll(@RequestHeader(name = "Authorization", required = false) String authorization,
      @RequestBody EnrollmentRequest request) {
    validate(request);
    var csr = certificateIssuer.validateCsr(request);
    if (agentRepository.isBlocked(request.agentId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent has been blocked");
    }
    tokenService.consumeValid(authorization, request.agentId());
    return certificateIssuer.issue(request, csr, "ENROLL");
  }

  @PostMapping("/renew")
  public EnrollmentResponse renew(HttpServletRequest servletRequest, @RequestBody EnrollmentRequest request) {
    var identity = certificateService.requireClientIdentity(servletRequest);
    validate(request);
    if (!identity.agentId().equals(request.agentId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent_id must match client certificate common name");
    }
    var csr = certificateIssuer.validateCsr(request);
    return certificateIssuer.issue(request, csr, "RENEW");
  }

  @PostMapping("/ingest")
  public ResponseEntity<Void> ingest(HttpServletRequest servletRequest,
      @RequestHeader(name = "Content-Encoding", required = false) String contentEncoding) throws IOException {
    var identity = certificateService.requireClientIdentity(servletRequest);
    ingestService.ingest(identity.agentId(), contentEncoding, servletRequest.getInputStream().readAllBytes());
    return ResponseEntity.accepted().build();
  }

  private void validate(EnrollmentRequest request) {
    if (request == null || request.agentId() == null || request.agentId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_id is required");
    }
    if (request.csrPem() == null || request.csrPem().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "csr_pem is required");
    }
  }
}
