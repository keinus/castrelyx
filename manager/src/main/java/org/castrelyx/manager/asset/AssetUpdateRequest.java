package org.castrelyx.manager.asset;

import jakarta.validation.constraints.NotBlank;

public record AssetUpdateRequest(
    @NotBlank String name,
    String location,
    String description) {
}
