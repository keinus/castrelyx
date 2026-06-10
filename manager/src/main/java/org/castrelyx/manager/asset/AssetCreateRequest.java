package org.castrelyx.manager.asset;

import jakarta.validation.constraints.NotBlank;

public record AssetCreateRequest(
    @NotBlank String name,
    AssetType assetType,
    String managementIp,
    String description) {
}
