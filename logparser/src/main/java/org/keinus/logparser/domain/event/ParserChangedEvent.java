package org.keinus.logparser.domain.event;

import org.keinus.logparser.domain.configuration.model.ParserAdapterConfig;
import org.springframework.context.ApplicationEvent;

public class ParserChangedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final ChangeType changeType;
    @SuppressWarnings("serial")
    private final ParserAdapterConfig config;
    private final Long parserId;

    public ParserChangedEvent(Object source, ChangeType changeType, ParserAdapterConfig config) {
        super(source);
        this.changeType = changeType;
        this.config = config;
        this.parserId = config.getId();
    }
    
    public ParserChangedEvent(Object source, ChangeType changeType, Long parserId) {
        super(source);
        this.changeType = changeType;
        this.config = null;
        this.parserId = parserId;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public ParserAdapterConfig getConfig() {
        return config;
    }
    
    public Long getParserId() {
        return parserId;
    }

    public enum ChangeType {
        CREATED, UPDATED, DELETED, ENABLED, DISABLED, PRIORITY_CHANGED
    }
}
