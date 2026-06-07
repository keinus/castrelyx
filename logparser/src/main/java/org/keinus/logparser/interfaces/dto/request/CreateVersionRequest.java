package org.keinus.logparser.interfaces.dto.request;

public record CreateVersionRequest(
        String versionName,
        String description,
        String createdBy
) {}
