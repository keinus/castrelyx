package org.castrelyx.castrelsign.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class DataSourceConfigTest {
  @TempDir Path directory;

  @Test
  void readersRemainAvailableWhileAnotherConnectionWrites() throws Exception {
    var properties = new CastrelSignProperties();
    properties.setDataDir(directory);
    var dataSource = new DataSourceConfig().dataSource(properties);
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("create table concurrent_poll (id integer primary key, status text)");
    jdbc.update("insert into concurrent_poll values (1, 'PENDING')");

    try (var writer = dataSource.getConnection(); var statement = writer.createStatement()) {
      writer.setAutoCommit(false);
      statement.executeUpdate("update concurrent_poll set status='RUNNING' where id=1");
      // A separate polling connection can still read the last committed state.
      assertThat(jdbc.queryForObject("select status from concurrent_poll where id=1", String.class))
          .isEqualTo("PENDING");
      writer.commit();
    }
    assertThat(jdbc.queryForObject("select status from concurrent_poll where id=1", String.class))
        .isEqualTo("RUNNING");
    assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("wal");
    assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(10000);
  }
}
