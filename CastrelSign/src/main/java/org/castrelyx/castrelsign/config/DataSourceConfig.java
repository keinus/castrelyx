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
    return dataSource;
  }
}
