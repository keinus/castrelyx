package org.castrelyx.castrelvault.config;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.sqlite.SQLiteDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataSourceConfig {
  @Bean
  DataSource dataSource(CastrelVaultProperties properties) throws Exception {
    if (properties.getDataDir() == null || properties.getDataDir().isBlank()) {
      throw new IllegalStateException("CASTRELVAULT_DATA_DIR is required");
    }
    Path dataDir = Path.of(properties.getDataDir());
    Files.createDirectories(dataDir);
    Path database = dataDir.resolve("vault.db");
    SQLiteDataSource dataSource = new SQLiteDataSource();
    dataSource.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
    dataSource.setEncoding("UTF-8");
    return dataSource;
  }
}
