package org.castrelyx.castrelsign.config;

import java.time.Duration;

import org.castrelyx.castrelsign.crypto.BouncyCastleSupport;
import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {
  @Bean
  CertificateAuthority certificateAuthority(CastrelSignProperties properties) throws Exception {
    BouncyCastleSupport.install();
    return CertificateAuthority.initialize(
        properties.getDataDir().resolve("certs"),
        properties.getTlsServerName(),
        Duration.ofDays(properties.getCertValidDays()),
        Duration.ofDays(properties.getRootValidDays()));
  }
}
