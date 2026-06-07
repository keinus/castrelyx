package org.keinus.logparser.domain.event;

import org.keinus.logparser.domain.configuration.model.OutputAdapterConfig;
import org.springframework.context.ApplicationEvent;

public class OutputAdapterChangedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final ChangeType changeType;
    @SuppressWarnings("serial")
    private final OutputAdapterConfig config;
    private final Long adapterId;

    public OutputAdapterChangedEvent(Object source, ChangeType changeType, OutputAdapterConfig config) {
        super(source);
        this.changeType = changeType;
        this.config = config;
        this.adapterId = config.getId();
    }
    
    public OutputAdapterChangedEvent(Object source, ChangeType changeType, Long adapterId) {
        super(source);
        this.changeType = changeType;
        this.config = null;
        this.adapterId = adapterId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public OutputAdapterConfig getConfig() {
        return config;
    }
    
    public Long getAdapterId() {
        return adapterId;
    }

    public enum ChangeType {
        CREATED, UPDATED, DELETED, ENABLED, DISABLED
    }
}
