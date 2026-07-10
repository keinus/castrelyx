package org.castrelyx.castrelsign.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

import org.castrelyx.castrelsign.config.CastrelSignProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerCertificateRenewalServiceTest {
  @TempDir
  Path tempDir;

  @Test
  void renewsExpiringPemAndPkcs12MaterialOnlyOnce() throws Exception {
    char[] password = "test-password".toCharArray();
    Path certDir = tempDir.resolve("certs");
    CertificateAuthority initial = CertificateAuthority.initialize(
        certDir, "manager.local", Duration.ofDays(3), Duration.ofDays(3650));
    initial.writeKeyStores(password);
    X509Certificate before = readCertificate(certDir.resolve("server.pem"));

    CastrelSignProperties properties = new CastrelSignProperties();
    properties.setDataDir(tempDir);
    properties.setTlsServerName("manager.local");
    properties.setCertValidDays(30);
    properties.setRootValidDays(3650);
    properties.setKeyStorePassword(String.valueOf(password));
    ServerCertificateRenewalService service = new ServerCertificateRenewalService(properties);

    assertThat(service.renewIfNeeded()).isTrue();
    X509Certificate after = readCertificate(certDir.resolve("server.pem"));
    assertThat(after.getSerialNumber()).isNotEqualTo(before.getSerialNumber());

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (var input = Files.newInputStream(certDir.resolve("server.p12"))) {
      keyStore.load(input, password);
    }
    assertThat(((X509Certificate) keyStore.getCertificate("castrelsign-server")).getSerialNumber())
        .isEqualTo(after.getSerialNumber());
    assertThat(service.renewIfNeeded()).isFalse();

    Files.delete(certDir.resolve("server.p12"));
    assertThat(service.renewIfNeeded()).isTrue();
    assertThat(certDir.resolve("server.p12")).exists();
    assertThat(service.renewIfNeeded()).isFalse();
  }

  private static X509Certificate readCertificate(Path path) throws Exception {
    try (var input = Files.newInputStream(path)) {
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
    }
  }
}
