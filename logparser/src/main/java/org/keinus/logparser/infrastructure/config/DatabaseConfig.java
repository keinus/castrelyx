package org.keinus.logparser.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@Slf4j
public class DatabaseConfig implements FlywayConfigurationCustomizer {

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @PostConstruct
    public void initDatabase() {
        ensureDatabaseDirectory();
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        ensureDatabaseDirectory();
    }

    private void ensureDatabaseDirectory() {
        if (datasourceUrl != null && datasourceUrl.startsWith("jdbc:sqlite:")
                && !datasourceUrl.contains(":memory:")) {

            // Extract file path from JDBC URL
            String dbPath = datasourceUrl.replace("jdbc:sqlite:", "");

            // Replace ${user.home} if present
            if (dbPath.contains("${user.home}")) {
                dbPath = dbPath.replace("${user.home}", System.getProperty("user.home"));
            }

            Path dbFile = Paths.get(dbPath);
            Path dbDirectory = dbFile.getParent();

            // Create parent directories if they don't exist
            if (dbDirectory != null && !Files.exists(dbDirectory)) {
                try {
                    Files.createDirectories(dbDirectory);
                    log.info("Created database directory: {}", dbDirectory);
                } catch (IOException e) {
                    log.error("Failed to create database directory: {}", dbDirectory, e);
                    throw new RuntimeException("Failed to create database directory", e);
                }
            }

            log.info("Database will be created at: {}", dbFile);
        }
    }
}
