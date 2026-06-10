package org.castrelyx.manager.asset;

import java.time.Instant;

public record AssetSourceBinding(
    long id,
    long assetId,
    SourceType sourceType,
    String sourceId,
    String sourceKey,
    int confidence,
    Instant lastSeenAt) {
}
