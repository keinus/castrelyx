package org.keinus.logparser.interfaces.exception;

public class ConfigImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String importFormat;
    private final String details;

    public ConfigImportException(String importFormat, String details, Throwable cause) {
        super(String.format("Failed to import configuration from %s: %s", importFormat, details), cause);
        this.importFormat = importFormat;
        this.details = details;
    }

    public ConfigImportException(String message) {
        super(message);
        this.importFormat = "UNKNOWN";
        this.details = message;
    }

    public String getImportFormat() {
        return importFormat;
    }

    public String getDetails() {
        return details;
    }
}
