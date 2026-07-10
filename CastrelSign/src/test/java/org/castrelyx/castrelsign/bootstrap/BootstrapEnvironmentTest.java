package org.castrelyx.castrelsign.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class BootstrapEnvironmentTest {
  private static final String BUNDLE_PREFIX = "spring.ssl.bundle.pem.castrelyx-generated";

  @TempDir
  Path tempDir;

  @Test
  void configuresReloadablePemBundleAndKeepsPkcs12ForLogparser() {
    MockEnvironment environment = baseEnvironment(tempDir);

    BootstrapEnvironment.apply(environment);

    Path certDir = tempDir.resolve("certs").toAbsolutePath().normalize();
    assertThat(environment.getProperty("server.ssl.bundle")).isEqualTo("castrelyx-generated");
    assertThat(environment.getProperty(BUNDLE_PREFIX + ".reload-on-update")).isEqualTo("true");
    assertThat(environment.getProperty(BUNDLE_PREFIX + ".keystore.certificate"))
        .isEqualTo(certDir.resolve("server.pem").toUri().toString());
    assertThat(environment.getProperty(BUNDLE_PREFIX + ".keystore.private-key"))
        .isEqualTo(certDir.resolve("server.key").toUri().toString());
    assertThat(environment.getProperty(BUNDLE_PREFIX + ".truststore.certificate"))
        .isEqualTo(certDir.resolve("ca.pem").toUri().toString());
    assertThat(certDir.resolve("server.p12")).exists();
    assertThat(certDir.resolve("truststore.p12")).exists();
  }

  @Test
  void preservesCompleteExternalKeyStoreConfigurationFromSpringEnvironment() {
    MockEnvironment environment = baseEnvironment(tempDir)
        .withProperty("server.ssl.key-store", "/operator/server.p12")
        .withProperty("server.ssl.key-store-password", "operator-password")
        .withProperty("server.ssl.trust-store", "/operator/trust.p12")
        .withProperty("server.ssl.trust-store-password", "operator-password");

    BootstrapEnvironment.apply(environment);

    assertThat(environment.getProperty("server.ssl.key-store")).isEqualTo("/operator/server.p12");
    assertThat(environment.getProperty("server.ssl.bundle")).isNull();
  }

  @Test
  void supplementsTrustStoreOnlyOverrideWithGeneratedServerIdentity() {
    Path generatedTrustStore = tempDir.resolve("certs/truststore.p12").toAbsolutePath().normalize();
    MockEnvironment environment = baseEnvironment(tempDir)
        .withProperty("server.ssl.trust-store", generatedTrustStore.toString());

    BootstrapEnvironment.apply(environment);

    assertThat(environment.getProperty("server.ssl.bundle")).isNull();
    assertThat(environment.getProperty("server.ssl.key-store"))
        .isEqualTo(tempDir.resolve("certs/server.p12").toAbsolutePath().normalize().toString());
    assertThat(environment.getProperty("server.ssl.key-store-password")).isEqualTo("test-password");
    assertThat(environment.getProperty("server.ssl.trust-store-password")).isEqualTo("test-password");
  }

  @Test
  void rejectsPartialExternalPemIdentity() {
    MockEnvironment environment = baseEnvironment(tempDir)
        .withProperty("server.ssl.certificate", "/operator/server.pem");

    assertThatThrownBy(() -> BootstrapEnvironment.apply(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasRootCauseMessage(
            "server.ssl.certificate and server.ssl.certificate-private-key must be configured together");
  }

  private static MockEnvironment baseEnvironment(Path dataDir) {
    return new MockEnvironment()
        .withProperty("castrelsign.data-dir", dataDir.toString())
        .withProperty("castrelsign.tls-server-name", "manager.local")
        .withProperty("castrelsign.key-store-password", "test-password")
        .withProperty("castrelsign.cert-valid-days", "30")
        .withProperty("castrelsign.root-valid-days", "3650");
  }
}
