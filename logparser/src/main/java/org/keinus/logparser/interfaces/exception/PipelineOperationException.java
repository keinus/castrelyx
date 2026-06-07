package org.keinus.logparser.interfaces.exception;

public class PipelineOperationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String operation;
    private final String pipelineStatus;

    public PipelineOperationException(String operation, String pipelineStatus, String details) {
        super(String.format("Failed to %s pipeline (status: %s): %s", operation, pipelineStatus, details));
        this.operation = operation;
        this.pipelineStatus = pipelineStatus;
    }

    public PipelineOperationException(String message, Throwable cause) {
        super(message, cause);
        this.operation = "UNKNOWN";
        this.pipelineStatus = "UNKNOWN";
    }

    public PipelineOperationException(String message) {
        super(message);
        this.operation = "UNKNOWN";
        this.pipelineStatus = "UNKNOWN";
    }

    public String getOperation() {
        return operation;
    }

    public String getPipelineStatus() {
        return pipelineStatus;
    }
}
