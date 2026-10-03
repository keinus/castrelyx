package org.castrelyx.castrelsign.config;

import java.time.Duration;

import org.castrelyx.castrelsign.crypto.BouncyCastleSupport;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {
  @Bean(destroyMethod = "close")
  org.castrelyx.castrelsign.kms.KeyManagementService keyManagementService(CastrelSignProperties properties) {
    return org.castrelyx.castrelsign.kms.KeyManagementService.open(properties);
  }
  @Bean
  CertificateAuthority certificateAuthority(CastrelSignProperties properties, org.castrelyx.castrelsign.kms.KeyManagementService kms) throws Exception {
    BouncyCastleSupport.install();
    return CertificateAuthority.initializeManaged(
        properties.getDataDir().resolve("certs"),
        properties.getTlsServerName(),
        Duration.ofDays(properties.getCertValidDays()),
        Duration.ofDays(properties.getRootValidDays()), properties.getKeyStorePassword().toCharArray(), kms);
  }
}
