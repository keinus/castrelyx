package org.castrelyx.castrelsign.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EnrollmentTokenRepository {
  private final JdbcTemplate jdbcTemplate;

  public EnrollmentTokenRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public TokenRecord create(String name, String tokenHash, String agentId, int maxUses, Instant expiresAt) {
    String createdAt = Instant.now().toString();
    jdbcTemplate.update("""
        insert into enrollment_tokens(name, token_hash, agent_id, max_uses, used_count, expires_at, created_at)
        values (?, ?, ?, ?, 0, ?, ?)
        """, name, tokenHash, agentId, maxUses, expiresAt.toString(), createdAt);
    return findByHash(tokenHash).orElseThrow();
  }

  public Optional<TokenRecord> findByHash(String tokenHash) {
    List<TokenRecord> tokens = jdbcTemplate.query("""
        select id, name, token_hash, agent_id, max_uses, used_count, expires_at, revoked_at, created_at, last_used_at, last_used_agent_id
        from enrollment_tokens
        where token_hash = ?
        """, (rs, rowNum) -> new TokenRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("token_hash"),
            rs.getString("agent_id"),
            rs.getInt("max_uses"),
            rs.getInt("used_count"),
            Instant.parse(rs.getString("expires_at")),
            instantOrNull(rs.getString("revoked_at")),
            Instant.parse(rs.getString("created_at")),
            instantOrNull(rs.getString("last_used_at")),
            rs.getString("last_used_agent_id")),
        tokenHash);
    if (tokens.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(tokens.get(0));
  }

  public List<TokenRecord> list() {
    return jdbcTemplate.query("""
        select id, name, token_hash, agent_id, max_uses, used_count, expires_at, revoked_at, created_at, last_used_at, last_used_agent_id
        from enrollment_tokens
        order by id desc
        """, (rs, rowNum) -> new TokenRecord(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("token_hash"),
            rs.getString("agent_id"),
            rs.getInt("max_uses"),
            rs.getInt("used_count"),
            Instant.parse(rs.getString("expires_at")),
            instantOrNull(rs.getString("revoked_at")),
            Instant.parse(rs.getString("created_at")),
            instantOrNull(rs.getString("last_used_at")),
            rs.getString("last_used_agent_id")));
  }

  public boolean markUsed(long id, String agentId, Instant now) {
    return jdbcTemplate.update("""
        update enrollment_tokens
        set used_count = used_count + 1, last_used_at = ?, last_used_agent_id = ?
        where id = ?
          and revoked_at is null
          and used_count < max_uses
          and expires_at > ?
        """, now.toString(), agentId, id, now.toString()) == 1;
  }

  public boolean revoke(long id) {
    return jdbcTemplate.update("""
        update enrollment_tokens
        set revoked_at = ?
        where id = ? and revoked_at is null
        """, Instant.now().toString(), id) == 1;
  }

  public int revokeUnusedForAgent(String agentId) {
    if (agentId == null || agentId.isBlank()) {
      return 0;
    }
    return jdbcTemplate.update("""
        update enrollment_tokens
        set revoked_at = ?
        where agent_id = ?
          and revoked_at is null
          and used_count < max_uses
        """, Instant.now().toString(), agentId);
  }

  private static Instant instantOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Instant.parse(value);
  }

  public record TokenRecord(
      long id,
      String name,
      String tokenHash,
      String agentId,
      int maxUses,
      int usedCount,
      Instant expiresAt,
      Instant revokedAt,
      Instant createdAt,
      Instant lastUsedAt,
      String lastUsedAgentId) {
  }
}
