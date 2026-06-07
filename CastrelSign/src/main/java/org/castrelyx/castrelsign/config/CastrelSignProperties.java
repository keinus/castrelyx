package org.castrelyx.castrelsign.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "castrelsign")
public class CastrelSignProperties {
  private Path dataDir = Path.of(System.getProperty("user.home"), "castrelsign");

  @NotBlank
  private String publicBaseUrl;

  private String enrollmentToken;

  @NotBlank
  private String adminToken;

  @Min(1)
  private int certValidDays = 30;

  @Min(1)
  private int rootValidDays = 3650;

  @NotBlank
  private String tlsServerName = "localhost";

  @NotBlank
  private String keyStorePassword = "changeit";

  public Path getDataDir() {
    return dataDir;
  }

  public void setDataDir(Path dataDir) {
    this.dataDir = dataDir;
  }

  public String getPublicBaseUrl() {
    return publicBaseUrl;
  }

  public void setPublicBaseUrl(String publicBaseUrl) {
    this.publicBaseUrl = trimRight(publicBaseUrl);
  }

  public String getEnrollmentToken() {
    return enrollmentToken;
  }

  public void setEnrollmentToken(String enrollmentToken) {
    this.enrollmentToken = enrollmentToken;
  }

  public String getAdminToken() {
    return adminToken;
  }

  public void setAdminToken(String adminToken) {
    this.adminToken = adminToken;
  }

  public int getCertValidDays() {
    return certValidDays;
  }

  public void setCertValidDays(int certValidDays) {
    this.certValidDays = certValidDays;
  }

  public int getRootValidDays() {
    return rootValidDays;
  }

  public void setRootValidDays(int rootValidDays) {
    this.rootValidDays = rootValidDays;
  }

  public String getTlsServerName() {
    return tlsServerName;
  }

  public void setTlsServerName(String tlsServerName) {
    this.tlsServerName = tlsServerName;
  }

  public String getKeyStorePassword() {
    return keyStorePassword;
  }

  public void setKeyStorePassword(String keyStorePassword) {
    this.keyStorePassword = keyStorePassword;
  }

  private static String trimRight(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("/+$", "");
  }
}
