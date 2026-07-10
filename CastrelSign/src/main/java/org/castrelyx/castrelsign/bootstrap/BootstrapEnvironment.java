package org.castrelyx.castrelsign.bootstrap;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.castrelyx.castrelsign.crypto.CertificateAuthority;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class BootstrapEnvironment {
  static final String GENERATED_PROPERTY_SOURCE = "castrelsignGeneratedTls";
  private static final String GENERATED_BUNDLE = "castrelyx-generated";

  private BootstrapEnvironment() {
  }

  public static void apply(ConfigurableEnvironment environment) {
    if (!environment.getProperty("castrelsign.tls-bootstrap-enabled", Boolean.class, true)) {
      return;
    }
    try {
      String dataDir = environment.getProperty(
          "castrelsign.data-dir",
          Path.of(System.getProperty("user.home"), "castrelsign").toString());
      String serverName = environment.getProperty("castrelsign.tls-server-name", "localhost");
      String password = environment.getProperty("castrelsign.key-store-password", "changeit");
      long certDays = environment.getProperty("castrelsign.cert-valid-days", Long.class, 30L);
      long rootDays = environment.getProperty("castrelsign.root-valid-days", Long.class, 3650L);

      Path certDir = Path.of(dataDir, "certs").toAbsolutePath().normalize();
      CertificateAuthority.refreshServerMaterial(
          certDir,
          serverName,
          Duration.ofDays(certDays),
          Duration.ofDays(rootDays),
          password.toCharArray());

      Map<String, Object> defaults = new LinkedHashMap<>();
      defaults.put("server.ssl.client-auth", "want");
      configureServerIdentity(environment, defaults, certDir, password);
      environment.getPropertySources().addLast(new MapPropertySource(GENERATED_PROPERTY_SOURCE, defaults));
    } catch (Exception e) {
      throw new IllegalStateException("failed to bootstrap CastrelSign TLS material", e);
    }
  }

  private static void configureServerIdentity(
      ConfigurableEnvironment environment,
      Map<String, Object> defaults,
      Path certDir,
      String password) {
    boolean externalBundle = hasText(environment, "server.ssl.bundle");
    boolean externalKeyStore = hasText(environment, "server.ssl.key-store");
    boolean externalCertificate = hasText(environment, "server.ssl.certificate");
    boolean externalPrivateKey = hasText(environment, "server.ssl.certificate-private-key");
    if (externalCertificate != externalPrivateKey) {
      throw new IllegalStateException(
          "server.ssl.certificate and server.ssl.certificate-private-key must be configured together");
    }
    if (externalBundle || externalKeyStore || externalCertificate) {
      return;
    }

    if (hasText(environment, "server.ssl.trust-store")) {
      // Preserve an operator trust store while supplying the otherwise missing
      // generated server identity. This compatibility mode uses discrete
      // PKCS12 settings; a complete external identity or no override is
      // preferred because those modes support explicit ownership/hot reload.
      defaults.put("server.ssl.key-store", certDir.resolve("server.p12").toString());
      defaults.put("server.ssl.key-store-type", "PKCS12");
      defaults.put("server.ssl.key-store-password", password);
      defaults.put("server.ssl.trust-store-type", "PKCS12");
      defaults.put("server.ssl.trust-store-password", password);
      return;
    }

    String prefix = "spring.ssl.bundle.pem." + GENERATED_BUNDLE;
    defaults.put(prefix + ".reload-on-update", "true");
    defaults.put(prefix + ".keystore.certificate", certDir.resolve("server.pem").toUri().toString());
    defaults.put(prefix + ".keystore.private-key", certDir.resolve("server.key").toUri().toString());
    defaults.put(prefix + ".truststore.certificate", certDir.resolve("ca.pem").toUri().toString());
    defaults.put("spring.ssl.bundle.watch.file.quiet-period", "2s");
    defaults.put("server.ssl.bundle", GENERATED_BUNDLE);
  }

  private static boolean hasText(ConfigurableEnvironment environment, String property) {
    String value = environment.getProperty(property);
    return value != null && !value.isBlank();
  }
}
