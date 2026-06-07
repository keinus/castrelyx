package org.keinus.logparser.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCreateDirectoryIfMissing() {
        DatabaseConfig config = new DatabaseConfig();
        Path dbPath = tempDir.resolve("subdir/test.db");
        String url = "jdbc:sqlite:" + dbPath.toString();
        
        ReflectionTestUtils.setField(config, "datasourceUrl", url);
        
        config.initDatabase();
        
        assertThat(Files.exists(tempDir.resolve("subdir"))).isTrue();
    }

    @Test
    void shouldCreateDirectoryBeforeFlywayMigration() {
        DatabaseConfig config = new DatabaseConfig();
        Path dbPath = tempDir.resolve("flyway/config.db");
        String url = "jdbc:sqlite:" + dbPath;

        ReflectionTestUtils.setField(config, "datasourceUrl", url);

        config.customize(new FluentConfiguration());

        assertThat(Files.exists(tempDir.resolve("flyway"))).isTrue();
    }

    @Test
    void shouldHandleMemoryDatabase() {
        DatabaseConfig config = new DatabaseConfig();
        ReflectionTestUtils.setField(config, "datasourceUrl", "jdbc:sqlite::memory:");
        
        config.initDatabase();
        // Should not throw any exception or create any directory
    }

    @Test
    void shouldHandleNullUrl() {
        DatabaseConfig config = new DatabaseConfig();
        ReflectionTestUtils.setField(config, "datasourceUrl", null);
        
        config.initDatabase();
        // Should not throw any exception
    }
}
