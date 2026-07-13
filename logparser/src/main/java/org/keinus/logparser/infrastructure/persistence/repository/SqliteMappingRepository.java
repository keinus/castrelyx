package org.keinus.logparser.infrastructure.persistence.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

@Repository
public class SqliteMappingRepository implements MappingRepository {
    private static final Logger log = LoggerFactory.getLogger(SqliteMappingRepository.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteMappingRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initTable() {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS mapping_config (" +
                         "message_type VARCHAR(255) PRIMARY KEY, " +
                         "config_json TEXT NOT NULL)");
        } catch (SQLException e) {
            log.error("Failed to initialize mapping_config table", e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    @Override
    public Optional<MappingConfiguration> findByMessageType(String messageType) {
        String sql = "SELECT config_json FROM mapping_config WHERE message_type = ?";
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, messageType);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String json = rs.getString("config_json");
                    return Optional.ofNullable(objectMapper.readValue(json, MappingConfiguration.class));
                }
            }
        } catch (Exception e) {
            log.error("Error loading mapping config for type: {}", messageType, e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return Optional.empty();
    }

    @Override
    public List<MappingConfiguration> findAll() {
        String sql = "SELECT config_json FROM mapping_config ORDER BY message_type";
        List<MappingConfiguration> configs = new ArrayList<>();
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                String json = rs.getString("config_json");
                MappingConfiguration config = objectMapper.readValue(json, MappingConfiguration.class);
                if (config != null) {
                    configs.add(config);
                }
            }
        } catch (Exception e) {
            log.error("Error loading mapping configs", e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
        return configs;
    }

    @Override
    public void save(MappingConfiguration config) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try (PreparedStatement pstmt = conn.prepareStatement(upsertSql(conn))) {
            
            String json = objectMapper.writeValueAsString(config);
            pstmt.setString(1, config.getMessageType());
            pstmt.setString(2, json);
            pstmt.executeUpdate();
            
            log.info("Saved mapping config for type: {}", config.getMessageType());
        } catch (Exception e) {
            log.error("Error saving mapping config for type: {}", config.getMessageType(), e);
            throw new RuntimeException("Failed to save mapping config", e);
        } finally {
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }

    private String upsertSql(Connection conn) throws SQLException {
        String databaseName = conn.getMetaData().getDatabaseProductName();
        if (databaseName != null && databaseName.toLowerCase().contains("h2")) {
            return "MERGE INTO mapping_config (message_type, config_json) KEY(message_type) VALUES (?, ?)";
        }
        return "INSERT OR REPLACE INTO mapping_config (message_type, config_json) VALUES (?, ?)";
    }
}
