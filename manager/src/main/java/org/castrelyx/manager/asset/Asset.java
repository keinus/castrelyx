package org.castrelyx.manager.asset;

import java.time.Instant;

public record Asset(
    long id,
    String assetUid,
    String name,
    AssetType assetType,
    String managementIp,
    String description,
    String status,
    Instant firstSeenAt,
    Instant lastSeenAt) {
}
