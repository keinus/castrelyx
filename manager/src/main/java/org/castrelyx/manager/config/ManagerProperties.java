package org.castrelyx.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "manager")
public record ManagerProperties(
    String cryptoKey,
    String publicBaseUrl,
    ClickHouse clickhouse,
    Vault vault,
    RemoteAccess remoteAccess) {
  @ConstructorBinding
  public ManagerProperties {
    if (vault == null) {
      vault = new Vault(false, "", "");
    }
    if (remoteAccess == null) {
      remoteAccess = new RemoteAccess(true, "keinus", 22, 600, 7200, 45, true);
    }
  }

  public ManagerProperties(String cryptoKey, String publicBaseUrl, ClickHouse clickhouse) {
    this(cryptoKey, publicBaseUrl, clickhouse, new Vault(false, "", ""), new RemoteAccess(true, "keinus", 22, 600, 7200, 45, true));
  }

  public ManagerProperties(String cryptoKey, String publicBaseUrl, ClickHouse clickhouse, Vault vault) {
    this(cryptoKey, publicBaseUrl, clickhouse, vault, new RemoteAccess(true, "keinus", 22, 600, 7200, 45, true));
  }

  public record ClickHouse(
      String endpointUrl,
      String database,
      String username,
      String password,
      String rawTable) {
  }

  public record Vault(
      boolean enabled,
      String baseUrl,
      String adminSessionToken,
      String keyStorePath,
      String keyStorePassword,
      String trustStorePath,
      String trustStorePassword,
      String migrationToken) {
    @ConstructorBinding
    public Vault {
    }

    public Vault(boolean enabled, String baseUrl, String adminSessionToken) {
      this(enabled, baseUrl, adminSessionToken, "", "", "", "", "");
    }
  }

  public record RemoteAccess(
      boolean enabled,
      String defaultSshUser,
      int defaultPort,
      long keyTtlSeconds,
      long sessionTtlSeconds,
      long authorizationTimeoutSeconds,
      boolean allowUnknownHostKeys) {
    @ConstructorBinding
    public RemoteAccess {
      if (defaultSshUser == null || defaultSshUser.isBlank()) {
        defaultSshUser = "keinus";
      }
      if (defaultPort <= 0) {
        defaultPort = 22;
      }
      if (keyTtlSeconds <= 0) {
        keyTtlSeconds = 600;
      }
      if (sessionTtlSeconds <= 0) {
        sessionTtlSeconds = 7200;
      }
      if (authorizationTimeoutSeconds <= 0) {
        authorizationTimeoutSeconds = 45;
      }
    }
  }
}
