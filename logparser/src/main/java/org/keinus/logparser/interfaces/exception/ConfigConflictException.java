package org.keinus.logparser.interfaces.exception;

public class ConfigConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String conflictType;
    private final String details;

    public ConfigConflictException(String conflictType, String details) {
        super(String.format("Configuration conflict: %s - %s", conflictType, details));
        this.conflictType = conflictType;
        this.details = details;
    }

    public ConfigConflictException(String message) {
        super(message);
        this.conflictType = "UNKNOWN";
        this.details = message;
    }

    public String getConflictType() {
        return conflictType;
    }

    public String getDetails() {
        return details;
    }
}
