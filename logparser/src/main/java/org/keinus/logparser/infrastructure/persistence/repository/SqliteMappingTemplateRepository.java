package org.keinus.logparser.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.keinus.logparser.domain.model.mapping.MappingConfiguration;
import org.keinus.logparser.domain.model.mapping.MappingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class SqliteMappingTemplateRepository implements MappingTemplateRepository {
    private static final Logger log = LoggerFactory.getLogger(SqliteMappingTemplateRepository.class);

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteMappingTemplateRepository(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS mapping_templates (
                      id VARCHAR(36) PRIMARY KEY,
                      name VARCHAR(255) NOT NULL UNIQUE,
                      description TEXT,
                      source_message_type VARCHAR(255),
                      config_json TEXT NOT NULL,
                      created_at VARCHAR(64) NOT NULL,
                      updated_at VARCHAR(64) NOT NULL
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mapping_templates_name ON mapping_templates(name)");
        } catch (SQLException e) {
            log.error("Failed to initialize mapping_templates table", e);
        }
    }

    @Override
    public List<MappingTemplate> findAll() {
        String sql = """
                SELECT id, name, description, source_message_type, config_json, created_at, updated_at
                FROM mapping_templates
                ORDER BY lower(name), name
                """;
        List<MappingTemplate> templates = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                templates.add(readTemplate(rs));
            }
        } catch (Exception e) {
            log.error("Error loading mapping templates", e);
        }
        return templates;
    }

    @Override
    public Optional<MappingTemplate> findById(String id) {
        String sql = """
                SELECT id, name, description, source_message_type, config_json, created_at, updated_at
                FROM mapping_templates
                WHERE id = ?
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readTemplate(rs));
                }
            }
        } catch (Exception e) {
            log.error("Error loading mapping template id={}", id, e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<MappingTemplate> findByName(String name) {
        String sql = """
                SELECT id, name, description, source_message_type, config_json, created_at, updated_at
                FROM mapping_templates
                WHERE lower(name) = lower(?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readTemplate(rs));
                }
            }
        } catch (Exception e) {
            log.error("Error loading mapping template name={}", name, e);
        }
        return Optional.empty();
    }

    @Override
    public MappingTemplate save(MappingTemplate template) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(upsertSql(conn))) {
            stmt.setString(1, template.getId());
            stmt.setString(2, template.getName());
            stmt.setString(3, template.getDescription());
            stmt.setString(4, template.getSourceMessageType());
            stmt.setString(5, objectMapper.writeValueAsString(template.getConfig()));
            stmt.setString(6, template.getCreatedAt().toString());
            stmt.setString(7, template.getUpdatedAt().toString());
            stmt.executeUpdate();
            return template;
        } catch (Exception e) {
            log.error("Error saving mapping template name={}", template.getName(), e);
            throw new RuntimeException("Failed to save mapping template", e);
        }
    }

    @Override
    public void deleteById(String id) {
        String sql = "DELETE FROM mapping_templates WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            log.error("Error deleting mapping template id={}", id, e);
            throw new RuntimeException("Failed to delete mapping template", e);
        }
    }

    private MappingTemplate readTemplate(ResultSet rs) throws Exception {
        MappingTemplate template = new MappingTemplate();
        template.setId(rs.getString("id"));
        template.setName(rs.getString("name"));
        template.setDescription(rs.getString("description"));
        template.setSourceMessageType(rs.getString("source_message_type"));
        template.setConfig(objectMapper.readValue(rs.getString("config_json"), MappingConfiguration.class));
        template.setCreatedAt(Instant.parse(rs.getString("created_at")));
        template.setUpdatedAt(Instant.parse(rs.getString("updated_at")));
        return template;
    }

    private String upsertSql(Connection conn) throws SQLException {
        String databaseName = conn.getMetaData().getDatabaseProductName();
        if (databaseName != null && databaseName.toLowerCase().contains("h2")) {
            return """
                    MERGE INTO mapping_templates
                    (id, name, description, source_message_type, config_json, created_at, updated_at)
                    KEY(id) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
        }
        return """
                INSERT OR REPLACE INTO mapping_templates
                (id, name, description, source_message_type, config_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
    }
}
