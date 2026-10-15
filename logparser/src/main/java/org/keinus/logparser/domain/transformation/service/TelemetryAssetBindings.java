package org.keinus.logparser.domain.transformation.service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistent replica of Manager's unambiguous address-to-asset bindings. */
@Service
public class TelemetryAssetBindings {
    private final JdbcTemplate jdbc;
    private volatile Map<String, String> aliases;

    @Autowired
    public TelemetryAssetBindings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.aliases = Map.of();
    }

    public TelemetryAssetBindings(Map<String, String> aliases) {
        this.jdbc = null;
        this.aliases = Map.copyOf(aliases);
    }

    @PostConstruct
    public void load() {
        if (jdbc == null) return;
        Map<String, String> loaded = new LinkedHashMap<>();
        jdbc.query("select address, asset_uid from telemetry_asset_bindings", rs -> {
            loaded.put(rs.getString("address"), rs.getString("asset_uid"));
        });
        aliases = Map.copyOf(loaded);
    }

    public String resolve(String addressOrUid) {
        return aliases.getOrDefault(addressOrUid, addressOrUid);
    }

    public Map<String, String> snapshot() { return aliases; }

    @Transactional
    public synchronized void replace(Map<String, String> replacement) {
        if (replacement == null || replacement.size() > 10000)
            throw new IllegalArgumentException("At most 10000 asset bindings are allowed");
        replacement.forEach((address, uid) -> {
            if (address == null || address.isBlank() || address.length() > 120 || uid == null
                    || !uid.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,119}"))
                throw new IllegalArgumentException("Invalid asset binding");
        });
        Map<String, String> snapshot = Map.copyOf(replacement);
        if (jdbc != null) {
            jdbc.update("delete from telemetry_asset_bindings");
            snapshot.forEach((address, uid) -> jdbc.update(
                    "insert into telemetry_asset_bindings(address, asset_uid) values (?, ?)", address, uid));
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { aliases = snapshot; }
                    });
        } else {
            aliases = snapshot;
        }
    }
}
