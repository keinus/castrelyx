package org.castrelyx.castrelvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "castrelvault")
public class CastrelVaultProperties {
  private String dataDir = "/var/lib/castrelvault";
  private String masterKeys;
  private String activeMasterKeyId;
  private String bootstrapAdminUsername;
  private String bootstrapAdminPassword;
  private long sessionTtlSeconds = 3600;
  private String castrelsignBaseUrl;
  private String castrelsignAdminToken;
  private String castrelsignCaCertPath;
  private String managerBaseUrl;
  private String managerMigrationToken;
  private String tlsKeyStorePath;
  private String tlsKeyStorePassword;
  private String tlsTrustStorePath;
  private String tlsTrustStorePassword;

  public String getDataDir() {
    return dataDir;
  }

  public void setDataDir(String dataDir) {
    this.dataDir = dataDir;
  }

  public String getMasterKeys() {
    return masterKeys;
  }

  public void setMasterKeys(String masterKeys) {
    this.masterKeys = masterKeys;
  }

  public String getActiveMasterKeyId() {
    return activeMasterKeyId;
  }

  public void setActiveMasterKeyId(String activeMasterKeyId) {
    this.activeMasterKeyId = activeMasterKeyId;
  }

  public String getBootstrapAdminUsername() {
    return bootstrapAdminUsername;
  }

  public void setBootstrapAdminUsername(String bootstrapAdminUsername) {
    this.bootstrapAdminUsername = bootstrapAdminUsername;
  }

  public String getBootstrapAdminPassword() {
    return bootstrapAdminPassword;
  }

  public void setBootstrapAdminPassword(String bootstrapAdminPassword) {
    this.bootstrapAdminPassword = bootstrapAdminPassword;
  }

  public long getSessionTtlSeconds() {
    return sessionTtlSeconds;
  }

  public void setSessionTtlSeconds(long sessionTtlSeconds) {
    this.sessionTtlSeconds = sessionTtlSeconds;
  }

  public String getCastrelsignBaseUrl() {
    return castrelsignBaseUrl;
  }

  public void setCastrelsignBaseUrl(String castrelsignBaseUrl) {
    this.castrelsignBaseUrl = castrelsignBaseUrl;
  }

  public String getCastrelsignAdminToken() {
    return castrelsignAdminToken;
  }

  public void setCastrelsignAdminToken(String castrelsignAdminToken) {
    this.castrelsignAdminToken = castrelsignAdminToken;
  }

  public String getCastrelsignCaCertPath() {
    return castrelsignCaCertPath;
  }

  public void setCastrelsignCaCertPath(String castrelsignCaCertPath) {
    this.castrelsignCaCertPath = castrelsignCaCertPath;
  }

  public String getManagerBaseUrl() {
    return managerBaseUrl;
  }

  public void setManagerBaseUrl(String managerBaseUrl) {
    this.managerBaseUrl = managerBaseUrl;
  }

  public String getManagerMigrationToken() {
    return managerMigrationToken;
  }

  public void setManagerMigrationToken(String managerMigrationToken) {
    this.managerMigrationToken = managerMigrationToken;
  }

  public String getTlsKeyStorePath() {
    return tlsKeyStorePath;
  }

  public void setTlsKeyStorePath(String tlsKeyStorePath) {
    this.tlsKeyStorePath = tlsKeyStorePath;
  }

  public String getTlsKeyStorePassword() {
    return tlsKeyStorePassword;
  }

  public void setTlsKeyStorePassword(String tlsKeyStorePassword) {
    this.tlsKeyStorePassword = tlsKeyStorePassword;
  }

  public String getTlsTrustStorePath() {
    return tlsTrustStorePath;
  }

  public void setTlsTrustStorePath(String tlsTrustStorePath) {
    this.tlsTrustStorePath = tlsTrustStorePath;
  }

  public String getTlsTrustStorePassword() {
    return tlsTrustStorePassword;
  }

  public void setTlsTrustStorePassword(String tlsTrustStorePassword) {
    this.tlsTrustStorePassword = tlsTrustStorePassword;
  }
}
