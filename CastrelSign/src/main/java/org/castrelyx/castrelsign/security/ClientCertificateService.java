package org.castrelyx.castrelsign.security;

import java.security.cert.X509Certificate;

import javax.naming.ldap.LdapName;

import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;

@Service
public class ClientCertificateService {
  private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
  private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
  private final CertificateAuthority authority;

  public ClientCertificateService(CertificateAuthority authority) {
    this.authority = authority;
  }

  public ClientIdentity requireClientIdentity(HttpServletRequest request) {
    Object attribute = request.getAttribute(CERTIFICATE_ATTRIBUTE);
    if (!(attribute instanceof X509Certificate[] certificates) || certificates.length == 0) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate is required");
    }
    X509Certificate certificate = certificates[0];
    try {
      certificate.checkValidity();
      certificate.verify(authority.rootCertificate().getPublicKey());
      requireClientAuth(certificate);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate is invalid", e);
    }
    String commonName = commonName(certificate);
    if (commonName == null || commonName.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "client certificate common name is missing");
    }
    return new ClientIdentity(commonName, certificate);
  }

  private void requireClientAuth(X509Certificate certificate) throws Exception {
    if (certificate.getBasicConstraints() != -1) {
      throw new IllegalArgumentException("client certificate must not be a CA certificate");
    }
    var usages = certificate.getExtendedKeyUsage();
    if (usages == null || !usages.contains(CLIENT_AUTH_EKU)) {
      throw new IllegalArgumentException("client certificate must include clientAuth extended key usage");
    }
  }

  private String commonName(X509Certificate certificate) {
    try {
      LdapName name = new LdapName(certificate.getSubjectX500Principal().getName());
      for (var rdn : name.getRdns()) {
        if ("CN".equalsIgnoreCase(rdn.getType())) {
          return String.valueOf(rdn.getValue());
        }
      }
      return null;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "failed to parse client certificate subject", e);
    }
  }

  public record ClientIdentity(String agentId, X509Certificate certificate) {
  }
}
