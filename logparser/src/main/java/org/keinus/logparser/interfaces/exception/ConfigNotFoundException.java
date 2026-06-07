package org.keinus.logparser.interfaces.exception;

public class ConfigNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String entityType;
    @SuppressWarnings("serial")
    private final Object entityId;

    public ConfigNotFoundException(String entityType, Object entityId) {
        super(String.format("%s with id '%s' not found", entityType, entityId));
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public ConfigNotFoundException(String message) {
        super(message);
        this.entityType = null;
        this.entityId = null;
    }

    public String getEntityType() {
        return entityType;
    }

    public Object getEntityId() {
        return entityId;
    }
}
