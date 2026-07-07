package org.castrelyx.manager.remote;

import java.time.Instant;

public record RemoteAccessSession(
    String id,
    Long assetId,
    String assetUid,
    String agentId,
    String sshUser,
    String targetHost,
    int targetPort,
    String status,
    String publicKeyFingerprint,
    String authorizationTaskId,
    String revokeTaskId,
    Long createdBy,
    String createdByUsername,
    Instant createdAt,
    Instant expiresAt,
    Instant connectedAt,
    Instant closedAt,
    String closeReason,
    String lastError) {
}
