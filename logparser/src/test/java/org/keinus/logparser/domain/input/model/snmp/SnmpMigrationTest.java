package org.keinus.logparser.domain.input.model.snmp;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SnmpMigrationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void permitsNotificationTypesOnFreshAndUpgradedDatabase(boolean upgrade) throws Exception {
        String url = "jdbc:sqlite:" + temporary.resolve("config.db");
        if (upgrade) Flyway.configure().dataSource(url, null, null).target("8").load().migrate();
        Flyway.configure().dataSource(url, null, null).load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            for (String type : new String[]{"SnmpInputAdapter", "snmp", "SnmpNotificationInputAdapter", "snmp_notification"}) {
                statement.execute("INSERT INTO input_adapters(type,messagetype) VALUES ('" + type + "','test')");
            }
            statement.execute("UPDATE input_adapters SET type='snmp_notification'");
            assertThrows(SQLException.class, () -> statement.execute("UPDATE input_adapters SET type='invalid'"));
        }
    }
}
