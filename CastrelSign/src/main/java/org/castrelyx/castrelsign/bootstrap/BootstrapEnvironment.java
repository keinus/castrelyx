package org.castrelyx.castrelsign.bootstrap;

import java.nio.file.Path;
import java.time.Duration;

import org.castrelyx.castrelsign.crypto.CertificateAuthority;

public final class BootstrapEnvironment {
  private BootstrapEnvironment() {
  }

  public static void apply() {
    try {
      String dataDir = value("castrelsign.data-dir", "CASTRELSIGN_DATA_DIR",
          Path.of(System.getProperty("user.home"), "castrelsign").toString());
      String serverName = value("castrelsign.tls-server-name", "CASTRELSIGN_TLS_SERVER_NAME", "localhost");
      String password = value("castrelsign.key-store-password", "CASTRELSIGN_KEYSTORE_PASSWORD", "changeit");
      long certDays = Long.parseLong(value("castrelsign.cert-valid-days", "CASTRELSIGN_CERT_VALID_DAYS", "30"));
      long rootDays = Long.parseLong(value("castrelsign.root-valid-days", "CASTRELSIGN_ROOT_VALID_DAYS", "3650"));

      CertificateAuthority authority = CertificateAuthority.initialize(
          Path.of(dataDir, "certs"),
          serverName,
          Duration.ofDays(certDays),
          Duration.ofDays(rootDays));
      CertificateAuthority.KeyStoreFiles stores = authority.writeKeyStores(password.toCharArray());

      setIfAbsent("castrelsign.data-dir", dataDir);
      setIfAbsent("server.ssl.key-store", stores.keyStorePath().toString());
      setIfAbsent("server.ssl.key-store-type", "PKCS12");
      setIfAbsent("server.ssl.key-store-password", password);
      setIfAbsent("server.ssl.trust-store", stores.trustStorePath().toString());
      setIfAbsent("server.ssl.trust-store-type", "PKCS12");
      setIfAbsent("server.ssl.trust-store-password", password);
      setIfAbsent("server.ssl.client-auth", "want");
    } catch (Exception e) {
      throw new IllegalStateException("failed to bootstrap CastrelSign TLS material", e);
    }
  }

  private static String value(String property, String env, String fallback) {
    String configured = System.getProperty(property);
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    configured = System.getenv(env);
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    return fallback;
  }

  private static void setIfAbsent(String key, String value) {
    if (System.getProperty(key) == null || System.getProperty(key).isBlank()) {
      System.setProperty(key, value);
    }
  }
}
