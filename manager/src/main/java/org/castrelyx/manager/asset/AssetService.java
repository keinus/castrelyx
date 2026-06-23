package org.castrelyx.manager.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AssetService {
  private final JdbcTemplate jdbcTemplate;

  public AssetService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public List<Asset> listAssets() {
    return jdbcTemplate.query("""
        select id, asset_uid, name, asset_type, management_ip, location, description, status, first_seen_at, last_seen_at
        from assets
        order by id desc
        """, AssetService::asset);
  }

  public Asset createManualAsset(AssetCreateRequest request) {
    AssetType type = request.assetType() == null ? AssetType.UNKNOWN : request.assetType();
    String assetUid = "manual-" + UUID.randomUUID();
    Instant now = Instant.now();
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var ps = connection.prepareStatement("""
          insert into assets(asset_uid, name, asset_type, management_ip, location, description, status, first_seen_at, last_seen_at, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?, ?)
          """, new String[] {"id"});
      ps.setString(1, assetUid);
      ps.setString(2, normalizeRequired(request.name()));
      ps.setString(3, type.name());
      ps.setString(4, normalizeOptional(request.managementIp()));
      ps.setString(5, normalizeOptional(request.location()));
      ps.setString(6, normalizeOptional(request.description()));
      ps.setTimestamp(7, Timestamp.from(now));
      ps.setTimestamp(8, Timestamp.from(now));
      ps.setTimestamp(9, Timestamp.from(now));
      ps.setTimestamp(10, Timestamp.from(now));
      return ps;
    }, keyHolder);
    return getAsset(keyHolder.getKey().longValue());
  }

  public Asset getAsset(long id) {
    return jdbcTemplate.queryForObject("""
        select id, asset_uid, name, asset_type, management_ip, location, description, status, first_seen_at, last_seen_at
        from assets
        where id = ?
        """, AssetService::asset, id);
  }

  public Asset updateEditableFields(long id, AssetUpdateRequest request) {
    Instant now = Instant.now();
    jdbcTemplate.update("""
        update assets
        set name = ?, location = ?, description = ?, updated_at = ?
        where id = ?
        """,
        normalizeRequired(request.name()),
        normalizeOptional(request.location()),
        normalizeOptional(request.description()),
        Timestamp.from(now),
        id);
    return getAsset(id);
  }

  public void deleteAsset(long id) {
    jdbcTemplate.update("update alert_instances set asset_id = null where asset_id = ?", id);
    jdbcTemplate.update("delete from asset_merge_candidates where primary_asset_id = ? or candidate_asset_id = ?", id, id);
    jdbcTemplate.update("delete from asset_source_bindings where asset_id = ?", id);
    jdbcTemplate.update("delete from assets where id = ?", id);
  }

  public Asset upsertObservedAsset(String assetUid, String name, AssetType type, String managementIp) {
    var existing = jdbcTemplate.query("select id from assets where asset_uid = ?", (rs, rowNum) -> rs.getLong("id"), assetUid);
    Instant now = Instant.now();
    if (existing.isEmpty()) {
      KeyHolder keyHolder = new GeneratedKeyHolder();
      jdbcTemplate.update(connection -> {
        var ps = connection.prepareStatement("""
            insert into assets(asset_uid, name, asset_type, management_ip, status, first_seen_at, last_seen_at, created_at, updated_at)
            values (?, ?, ?, ?, 'active', ?, ?, ?, ?)
            """, new String[] {"id"});
        ps.setString(1, assetUid);
        ps.setString(2, name == null || name.isBlank() ? assetUid : name);
        ps.setString(3, type == null ? AssetType.UNKNOWN.name() : type.name());
        ps.setString(4, managementIp);
        ps.setTimestamp(5, Timestamp.from(now));
        ps.setTimestamp(6, Timestamp.from(now));
        ps.setTimestamp(7, Timestamp.from(now));
        ps.setTimestamp(8, Timestamp.from(now));
        return ps;
      }, keyHolder);
      return getAsset(keyHolder.getKey().longValue());
    }
    jdbcTemplate.update("""
        update assets
        set name = coalesce(?, name), asset_type = ?, management_ip = coalesce(?, management_ip), last_seen_at = ?, updated_at = ?
        where id = ?
        """,
        name,
        type == null ? AssetType.UNKNOWN.name() : type.name(),
        managementIp,
        Timestamp.from(now),
        Timestamp.from(now),
        existing.getFirst());
    return getAsset(existing.getFirst());
  }

  public AssetSourceBinding bindSource(long assetId, SourceType sourceType, String sourceId, String sourceKey, int confidence) {
    Instant now = Instant.now();
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var ps = connection.prepareStatement("""
          insert into asset_source_bindings(asset_id, source_type, source_id, source_key, confidence, last_seen_at, created_at)
          values (?, ?, ?, ?, ?, ?, ?)
          """, new String[] {"id"});
      ps.setLong(1, assetId);
      ps.setString(2, sourceType.name());
      ps.setString(3, sourceId);
      ps.setString(4, sourceKey);
      ps.setInt(5, confidence);
      ps.setTimestamp(6, Timestamp.from(now));
      ps.setTimestamp(7, Timestamp.from(now));
      return ps;
    }, keyHolder);
    return source(keyHolder.getKey().longValue());
  }

  public List<AssetSourceBinding> sources(long assetId) {
    return jdbcTemplate.query("""
        select id, asset_id, source_type, source_id, source_key, confidence, last_seen_at
        from asset_source_bindings
        where asset_id = ?
        order by id
        """, AssetService::source, assetId);
  }

  public MergeCandidate createMergeCandidate(long primaryAssetId, long candidateAssetId, String reason, int confidence) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(connection -> {
      var ps = connection.prepareStatement("""
          insert into asset_merge_candidates(primary_asset_id, candidate_asset_id, reason, confidence, status, created_at)
          values (?, ?, ?, ?, 'pending', ?)
          """, new String[] {"id"});
      ps.setLong(1, primaryAssetId);
      ps.setLong(2, candidateAssetId);
      ps.setString(3, reason);
      ps.setInt(4, confidence);
      ps.setTimestamp(5, Timestamp.from(Instant.now()));
      return ps;
    }, keyHolder);
    return mergeCandidate(keyHolder.getKey().longValue());
  }

  public List<MergeCandidate> mergeCandidates() {
    return jdbcTemplate.query("""
        select id, primary_asset_id, candidate_asset_id, reason, confidence, status
        from asset_merge_candidates
        order by id desc
        """, AssetService::mergeCandidate);
  }

  public MergeCandidate acceptMergeCandidate(long id) {
    jdbcTemplate.update("update asset_merge_candidates set status = 'accepted' where id = ?", id);
    return mergeCandidate(id);
  }

  public MergeCandidate rejectMergeCandidate(long id) {
    jdbcTemplate.update("update asset_merge_candidates set status = 'rejected' where id = ?", id);
    return mergeCandidate(id);
  }

  private AssetSourceBinding source(long id) {
    return jdbcTemplate.queryForObject("""
        select id, asset_id, source_type, source_id, source_key, confidence, last_seen_at
        from asset_source_bindings
        where id = ?
        """, AssetService::source, id);
  }

  private MergeCandidate mergeCandidate(long id) {
    return jdbcTemplate.queryForObject("""
        select id, primary_asset_id, candidate_asset_id, reason, confidence, status
        from asset_merge_candidates
        where id = ?
        """, AssetService::mergeCandidate, id);
  }

  private static Asset asset(ResultSet rs, int rowNum) throws SQLException {
    return new Asset(
        rs.getLong("id"),
        rs.getString("asset_uid"),
        rs.getString("name"),
        AssetType.valueOf(rs.getString("asset_type")),
        rs.getString("management_ip"),
        rs.getString("location"),
        rs.getString("description"),
        rs.getString("status"),
        instant(rs.getTimestamp("first_seen_at")),
        instant(rs.getTimestamp("last_seen_at")));
  }

  private static AssetSourceBinding source(ResultSet rs, int rowNum) throws SQLException {
    return new AssetSourceBinding(
        rs.getLong("id"),
        rs.getLong("asset_id"),
        SourceType.valueOf(rs.getString("source_type")),
        rs.getString("source_id"),
        rs.getString("source_key"),
        rs.getInt("confidence"),
        instant(rs.getTimestamp("last_seen_at")));
  }

  private static MergeCandidate mergeCandidate(ResultSet rs, int rowNum) throws SQLException {
    return new MergeCandidate(
        rs.getLong("id"),
        rs.getLong("primary_asset_id"),
        rs.getLong("candidate_asset_id"),
        rs.getString("reason"),
        rs.getInt("confidence"),
        MergeCandidateStatus.valueOf(rs.getString("status").toUpperCase()));
  }

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  private static String normalizeRequired(String value) {
    return value == null ? null : value.trim();
  }

  private static String normalizeOptional(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
