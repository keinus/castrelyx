package org.castrelyx.castrelvault.persistence;

import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaInitializer implements InitializingBean {
  private final JdbcTemplate jdbcTemplate;

  public SchemaInitializer(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void afterPropertiesSet() {
    jdbcTemplate.execute("""
        create table if not exists vault_users (
          id integer primary key autoincrement,
          username text not null unique,
          password_hash text not null,
          role text not null,
          require_password_change integer not null,
          enabled integer not null,
          created_at text not null,
          updated_at text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists vault_sessions (
          id integer primary key autoincrement,
          token_hash text not null unique,
          user_id integer not null,
          expires_at text not null,
          created_at text not null,
          foreign key(user_id) references vault_users(id)
        )
        """);
    addColumnIfMissing("vault_sessions", "csrf_token_hash", "text");
    jdbcTemplate.execute("create index if not exists idx_vault_sessions_hash on vault_sessions(token_hash)");
    jdbcTemplate.execute("""
        create table if not exists vault_secrets (
          id text primary key,
          path text not null unique,
          display_name text not null,
          type text not null,
          tags_json text not null,
          description text,
          enabled integer not null,
          deleted integer not null,
          created_at text not null,
          updated_at text not null,
          current_version_id integer,
          foreign key(current_version_id) references vault_secret_versions(id)
        )
        """);
    jdbcTemplate.execute("create index if not exists idx_vault_secrets_path on vault_secrets(path)");
    jdbcTemplate.execute("""
        create table if not exists vault_secret_versions (
          id integer primary key autoincrement,
          secret_id text not null,
          version_number integer not null,
          key_id text not null,
          wrapped_dek_nonce blob not null,
          encrypted_dek blob not null,
          encryption_nonce blob not null,
          ciphertext blob not null,
          payload_content_hash text not null,
          created_at text not null,
          creator_principal text not null,
          foreign key(secret_id) references vault_secrets(id),
          unique(secret_id, version_number)
        )
        """);
    jdbcTemplate.execute("create index if not exists idx_vault_secret_versions_secret on vault_secret_versions(secret_id)");
    jdbcTemplate.execute("""
        create table if not exists vault_audit_events (
          id integer primary key autoincrement,
          timestamp text not null,
          actor_type text not null,
          actor_id text,
          secret_path text,
          secret_version integer,
          action text not null,
          result text not null,
          reason text,
          source_metadata text not null
        )
        """);
    jdbcTemplate.execute("create index if not exists idx_vault_audit_events_time on vault_audit_events(timestamp)");
    jdbcTemplate.execute("""
        create table if not exists vault_application_access_cache (
          principal_id text not null,
          certificate_serial text not null,
          permission text not null,
          decision text not null,
          reason text,
          expires_at text not null,
          checked_at text not null,
          primary key(principal_id, certificate_serial, permission)
        )
        """);
  }

  private void addColumnIfMissing(String table, String column, String definition) {
    List<String> columns = jdbcTemplate.query("pragma table_info(" + table + ")",
        (rs, rowNum) -> rs.getString("name"));
    if (!columns.contains(column)) {
      jdbcTemplate.execute("alter table " + table + " add column " + column + " " + definition);
    }
  }
}
