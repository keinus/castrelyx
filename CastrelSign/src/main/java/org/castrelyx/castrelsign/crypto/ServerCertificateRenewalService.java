package org.castrelyx.castrelsign.crypto;

import java.time.Duration;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServerCertificateRenewalService {
  private static final Logger log = LoggerFactory.getLogger(ServerCertificateRenewalService.class);

  private final CastrelSignProperties properties;

  public ServerCertificateRenewalService(CastrelSignProperties properties) {
    this.properties = properties;
  }

  @Scheduled(
      fixedDelayString = "${castrelsign.server-cert-check-interval-ms:3600000}",
      initialDelayString = "${castrelsign.server-cert-check-initial-delay-ms:60000}")
  public void renewOnSchedule() {
    try {
      renewIfNeeded();
    } catch (Exception e) {
      log.error("Failed to refresh CastrelSign server TLS material", e);
    }
  }

  boolean renewIfNeeded() throws Exception {
    CertificateAuthority.RefreshResult result = CertificateAuthority.refreshServerMaterial(
        properties.getDataDir().resolve("certs"),
        properties.getTlsServerName(),
        Duration.ofDays(properties.getCertValidDays()),
        Duration.ofDays(properties.getRootValidDays()),
        properties.getKeyStorePassword().toCharArray());
    if (result.keyStoresChanged()) {
      log.info(
          "Refreshed CastrelSign server TLS material; reloadable PEM consumers rotate automatically and PKCS12 consumers can reload the updated stores");
    }
    return result.keyStoresChanged();
  }
}
