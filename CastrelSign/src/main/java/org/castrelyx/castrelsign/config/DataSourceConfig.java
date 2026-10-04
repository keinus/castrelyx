package org.castrelyx.castrelsign.config;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DataSourceConfig {
  @Bean
  DataSource dataSource(CastrelSignProperties properties) throws Exception {
    if (Files.exists(properties.getDataDir().resolve(".restore-in-progress"))) throw new IllegalStateException("incomplete backup restore; do not start the server");
    Files.createDirectories(properties.getDataDir());
    Path dbPath = properties.getDataDir().resolve("castrelsign.sqlite");
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.sqlite.JDBC");
    dataSource.setUrl("jdbc:sqlite:" + dbPath);
    // Agents poll concurrently with enrollment, audit and task-result writes.
    // WAL keeps readers available during writes; every new connection must also
    // wait for a short competing writer instead of failing the request.
    var connectionProperties = new java.util.Properties();
    connectionProperties.setProperty("busy_timeout", "10000");
    dataSource.setConnectionProperties(connectionProperties);
    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");
    }
    return dataSource;
  }
}
