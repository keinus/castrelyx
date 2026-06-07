package org.keinus.logparser.domain.event;

import org.keinus.logparser.domain.configuration.model.TransformConfig;
import org.springframework.context.ApplicationEvent;

public class TransformChangedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final ChangeType changeType;
    @SuppressWarnings("serial")
    private final TransformConfig config;
    private final Long transformId;

    public TransformChangedEvent(Object source, ChangeType changeType, TransformConfig config) {
        super(source);
        this.changeType = changeType;
        this.config = config;
        this.transformId = config.getId();
    }
    
    public TransformChangedEvent(Object source, ChangeType changeType, Long transformId) {
        super(source);
        this.changeType = changeType;
        this.config = null;
        this.transformId = transformId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public TransformConfig getConfig() {
        return config;
    }
    
    public Long getTransformId() {
        return transformId;
    }

    public enum ChangeType {
        CREATED, UPDATED, DELETED, ENABLED, DISABLED, PRIORITY_CHANGED
    }
}
