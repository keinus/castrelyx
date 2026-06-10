package org.castrelyx.manager.asset;

public record MergeCandidate(
    long id,
    long primaryAssetId,
    long candidateAssetId,
    String reason,
    int confidence,
    MergeCandidateStatus status) {
}
