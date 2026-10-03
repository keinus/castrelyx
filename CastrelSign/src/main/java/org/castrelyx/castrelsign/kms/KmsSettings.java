package org.castrelyx.castrelsign.kms;

import java.nio.file.Path;

/** Also bound before Spring starts, because TLS bootstrap uses the same key store. */
public class KmsSettings {
  private String mode = "development";
  private Path masterKeyFile;
  private Path administratorsFile;
  private String tenant = "castrelyx";
  private String environment = "development";
  private int signsPerMinute = 600;
  private int deletionGraceDays = 7;
  private int signingLifetimeDays = 365;
  private Path pkcs11Config;
  private Path pkcs11PinFile;
  public Path getPkcs11Config() { return pkcs11Config; }
  public void setPkcs11Config(Path value) { pkcs11Config = value; }
  public Path getPkcs11PinFile() { return pkcs11PinFile; }
  public void setPkcs11PinFile(Path value) { pkcs11PinFile = value; }
  public String getMode() { return mode; }
  public void setMode(String value) { mode = value; }
  public Path getMasterKeyFile() { return masterKeyFile; }
  public void setMasterKeyFile(Path value) { masterKeyFile = value; }
  public Path getAdministratorsFile() { return administratorsFile; }
  public void setAdministratorsFile(Path value) { administratorsFile = value; }
  public String getTenant() { return tenant; }
  public void setTenant(String value) { tenant = value; }
  public String getEnvironment() { return environment; }
  public void setEnvironment(String value) { environment = value; }
  public int getSignsPerMinute() { return signsPerMinute; }
  public void setSignsPerMinute(int value) { signsPerMinute = value; }
  public int getDeletionGraceDays() { return deletionGraceDays; }
  public void setDeletionGraceDays(int value) { deletionGraceDays = value; }
  public int getSigningLifetimeDays() { return signingLifetimeDays; }
  public void setSigningLifetimeDays(int value) { signingLifetimeDays = value; }
  public boolean production() { return "production".equals(mode); }
  public void validate(Path dataDir, String tlsPassword) {
    if ((pkcs11Config == null) != (pkcs11PinFile == null)) throw new IllegalStateException("PKCS11 configuration and PIN file must be configured together");
    if (pkcs11PinFile != null && pkcs11PinFile.toAbsolutePath().normalize().startsWith(dataDir.toAbsolutePath().normalize()))
      throw new IllegalStateException("PKCS11 PIN file must be outside the data directory");
    if (!"development".equals(mode) && !production()) throw new IllegalArgumentException("invalid KMS mode");
    if (tenant == null || tenant.isBlank() || environment == null || environment.isBlank()
        || signsPerMinute < 1 || deletionGraceDays < 7 || signingLifetimeDays < 1)
      throw new IllegalArgumentException("invalid KMS policy");
    if (production() && (masterKeyFile == null || administratorsFile == null
        || masterKeyFile.toAbsolutePath().normalize().startsWith(dataDir.toAbsolutePath().normalize())
        || administratorsFile.toAbsolutePath().normalize().startsWith(dataDir.toAbsolutePath().normalize())
        || "development".equals(environment) || tlsPassword == null || tlsPassword.length() < 16
        || "changeit".equals(tlsPassword)))
      throw new IllegalStateException("production requires an external master-key file, administrators file, environment and a strong TLS keystore password");
  }
}
