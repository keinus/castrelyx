package org.castrelyx.castrelsign.persistence;

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
        create table if not exists enrollment_tokens (
          id integer primary key autoincrement,
          name text not null,
          token_hash text not null unique,
          agent_id text,
          max_uses integer not null,
          used_count integer not null default 0,
          expires_at text not null,
          revoked_at text,
          created_at text not null,
          last_used_at text,
          last_used_agent_id text
        )
        """);
    jdbcTemplate.execute("create index if not exists idx_enrollment_tokens_hash on enrollment_tokens(token_hash)");
    jdbcTemplate.execute("create index if not exists idx_enrollment_tokens_agent on enrollment_tokens(agent_id)");
    jdbcTemplate.execute("""
        create table if not exists agents (
          agent_id text primary key,
          hostname text,
          version text,
          status text not null,
          first_seen_at text not null,
          last_seen_at text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists issued_certificates (
          id integer primary key autoincrement,
          agent_id text not null,
          serial_number text not null unique,
          subject_dn text not null,
          not_before text not null,
          not_after text not null,
          pem text not null,
          status text not null,
          issued_at text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists ingest_batches (
          id integer primary key autoincrement,
          agent_id text not null,
          tenant_id text,
          source text,
          source_id text not null,
          schema_version text,
          observed_at text,
          sent_at text,
          received_at text not null,
          raw_json text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists ingest_items (
          id integer primary key autoincrement,
          batch_id integer not null,
          kind text,
          type text,
          item_key text,
          payload_json text,
          foreign key(batch_id) references ingest_batches(id)
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists audit_events (
          id integer primary key autoincrement,
          event_type text not null,
          agent_id text,
          message text,
          created_at text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists agent_releases (
          id integer primary key autoincrement,
          version text not null,
          os text not null,
          arch text not null,
          channel text not null,
          status text not null,
          sha256 text not null,
          size_bytes integer not null,
          signature text not null,
          manifest_json text not null,
          artifact_path text not null,
          created_at text not null,
          activated_at text,
          revoked_at text
        )
        """);
    jdbcTemplate.execute("create index if not exists idx_agent_releases_lookup on agent_releases(os, arch, channel, status)");
    jdbcTemplate.execute("create unique index if not exists idx_agent_releases_artifact_path on agent_releases(artifact_path)");
    jdbcTemplate.execute("""
        create table if not exists agent_update_policies (
          policy_key text primary key,
          agent_id text,
          enabled integer not null,
          channel text not null,
          target_version text,
          updated_at text not null
        )
        """);
    jdbcTemplate.execute("""
        create table if not exists agent_update_attempts (
          id integer primary key autoincrement,
          deployment_id text not null unique,
          agent_id text not null,
          release_id integer not null,
          from_version text,
          status text not null,
          message text,
          created_at text not null,
          updated_at text not null,
          foreign key(release_id) references agent_releases(id)
        )
        """);
  }
}
