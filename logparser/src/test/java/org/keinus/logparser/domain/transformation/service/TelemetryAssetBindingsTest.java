package org.keinus.logparser.domain.transformation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TelemetryAssetBindingsTest {
    @TempDir Path directory;

    @Test
    void persistsAcrossRestartAndReplacesStaleBindings() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("bindings.db")));
        jdbc.execute("create table telemetry_asset_bindings(address varchar(120) primary key, asset_uid varchar(120) not null)");
        var first = new TelemetryAssetBindings(jdbc);
        first.load();
        first.replace(Map.of("192.168.50.22", "nas"));
        var restarted = new TelemetryAssetBindings(jdbc);
        restarted.load();
        assertThat(restarted.resolve("192.168.50.22")).isEqualTo("nas");
        assertThatThrownBy(() -> restarted.replace(Map.of("192.168.50.22", "")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(restarted.snapshot()).containsEntry("192.168.50.22", "nas");
        restarted.replace(Map.of());
        first.load();
        assertThat(first.resolve("192.168.50.22")).isEqualTo("192.168.50.22");
    }
}
