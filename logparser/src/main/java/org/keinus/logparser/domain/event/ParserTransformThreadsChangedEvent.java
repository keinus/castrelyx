package org.keinus.logparser.domain.event;

import org.springframework.context.ApplicationEvent;

import lombok.Getter;

@Getter
public class ParserTransformThreadsChangedEvent extends ApplicationEvent {
    private static final long serialVersionUID = 1L;

    private final ChangeType changeType;
    private final int threads;

    public ParserTransformThreadsChangedEvent(Object source, ChangeType changeType, int threads) {
        super(source);
        this.changeType = changeType;
        this.threads = threads;
    }
    
    public enum ChangeType {
        CREATED, UPDATED, DELETED
    }
}
