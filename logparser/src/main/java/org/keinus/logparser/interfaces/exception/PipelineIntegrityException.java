package org.keinus.logparser.interfaces.exception;

import java.util.List;

public class PipelineIntegrityException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("serial")
    private final List<String> integrityErrors;

    public PipelineIntegrityException(String message, List<String> integrityErrors) {
        super(message);
        this.integrityErrors = integrityErrors;
    }

    public PipelineIntegrityException(String message) {
        super(message);
        this.integrityErrors = List.of(message);
    }

    public List<String> getIntegrityErrors() {
        return integrityErrors;
    }
}
