package org.keinus.logparser.domain.event;

import org.keinus.logparser.domain.configuration.model.InputAdapterConfig;
import org.springframework.context.ApplicationEvent;

public class InputAdapterChangedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final ChangeType changeType;
    @SuppressWarnings("serial")
    private final InputAdapterConfig config;
    private final Long adapterId;

    public InputAdapterChangedEvent(Object source, ChangeType changeType, InputAdapterConfig config) {
        super(source);
        this.changeType = changeType;
        this.config = config;
        this.adapterId = config.getId();
    }
    
    public InputAdapterChangedEvent(Object source, ChangeType changeType, Long adapterId) {
        super(source);
        this.changeType = changeType;
        this.config = null;
        this.adapterId = adapterId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public InputAdapterConfig getConfig() {
        return config;
    }
    
    public Long getAdapterId() {
        return adapterId;
    }

    public enum ChangeType {
        CREATED, UPDATED, DELETED, ENABLED, DISABLED
    }
}
