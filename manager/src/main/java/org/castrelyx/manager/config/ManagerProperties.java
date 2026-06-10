package org.castrelyx.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "manager")
public record ManagerProperties(
    String cryptoKey,
    String publicBaseUrl,
    ClickHouse clickhouse) {
  public record ClickHouse(
      String endpointUrl,
      String database,
      String username,
      String password,
      String rawTable) {
  }
}
