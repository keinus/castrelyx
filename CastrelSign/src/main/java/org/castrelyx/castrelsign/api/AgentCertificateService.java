package org.castrelyx.castrelsign.api;

import java.security.cert.X509Certificate;
import java.time.Duration;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.castrelyx.castrelsign.crypto.CsrService;
import org.castrelyx.castrelsign.crypto.CsrService.ParsedCsr;
import org.castrelyx.castrelsign.crypto.PemUtil;
import org.castrelyx.castrelsign.persistence.AgentRepository;
import org.springframework.stereotype.Service;

@Service
public class AgentCertificateService {
  private final CastrelSignProperties properties;
  private final CertificateAuthority authority;
  private final CsrService csrService;
  private final AgentRepository repository;

  public AgentCertificateService(CastrelSignProperties properties, CertificateAuthority authority, CsrService csrService,
      AgentRepository repository) {
    this.properties = properties;
    this.authority = authority;
    this.csrService = csrService;
    this.repository = repository;
  }

  public EnrollmentResponse issue(EnrollmentRequest request, String auditEvent) {
    return issue(request, validateCsr(request), auditEvent);
  }

  public ParsedCsr validateCsr(EnrollmentRequest request) {
    return csrService.parseAndValidate(request.csrPem(), request.agentId());
  }

  public EnrollmentResponse issue(EnrollmentRequest request, ParsedCsr csr, String auditEvent) {
    X509Certificate certificate = authority.signAgentCertificate(csr, Duration.ofDays(properties.getCertValidDays()));
    repository.upsertAgent(request.agentId(), request.hostname(), request.version());
    repository.saveCertificate(request.agentId(), certificate);
    repository.audit(auditEvent, request.agentId(), "issued client certificate " + certificate.getSerialNumber().toString(16));
    return new EnrollmentResponse(
        request.agentId(),
        authority.rootCertificatePem(),
        PemUtil.certificateToPem(certificate),
        certificate.getNotAfter().toInstant().toString(),
        properties.getPublicBaseUrl() + "/api/agent/ingest");
  }
}
