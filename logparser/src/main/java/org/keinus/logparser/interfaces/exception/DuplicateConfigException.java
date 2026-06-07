package org.keinus.logparser.interfaces.exception;

public class DuplicateConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String entityType;
    private final String conflictField;
    @SuppressWarnings("serial")
    private final Object conflictValue;

    public DuplicateConfigException(String entityType, String conflictField, Object conflictValue) {
        super(String.format("Duplicate %s: %s='%s' already exists", entityType, conflictField, conflictValue));
        this.entityType = entityType;
        this.conflictField = conflictField;
        this.conflictValue = conflictValue;
    }

    public DuplicateConfigException(String message) {
        super(message);
        this.entityType = null;
        this.conflictField = null;
        this.conflictValue = null;
    }

    public String getEntityType() {
        return entityType;
    }

    public String getConflictField() {
        return conflictField;
    }

    public Object getConflictValue() {
        return conflictValue;
    }
}
