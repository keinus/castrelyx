package org.keinus.logparser.interfaces.exception;

public class VersionNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final Long versionId;

    public VersionNotFoundException(Long versionId) {
        super(String.format("Configuration version with id '%s' not found", versionId));
        this.versionId = versionId;
    }

    public VersionNotFoundException(String message) {
        super(message);
        this.versionId = null;
    }

    public Long getVersionId() {
        return versionId;
    }
}
