package org.keinus.logparser.interfaces.exception;

public enum ErrorCode {
    // Configuration Errors (1000-1099)
    CONFIG_NOT_FOUND("1000", "Configuration not found"),
    CONFIG_VALIDATION_FAILED("1001", "Configuration validation failed"),
    DUPLICATE_CONFIG("1002", "Duplicate configuration"),
    CONFIG_CONFLICT("1003", "Configuration conflict"),
    INVALID_ADAPTER_TYPE("1004", "Invalid adapter type"),

    // Pipeline Errors (1100-1199)
    PIPELINE_INTEGRITY_VIOLATION("1100", "Pipeline integrity violation"),
    PIPELINE_OPERATION_FAILED("1101", "Pipeline operation failed"),
    PIPELINE_RELOAD_IN_PROGRESS("1102", "Pipeline reload already in progress"),
    PIPELINE_NOT_RUNNING("1103", "Pipeline is not running"),

    // Version Errors (1200-1299)
    VERSION_NOT_FOUND("1200", "Configuration version not found"),
    VERSION_ALREADY_ACTIVE("1201", "Version is already active"),
    VERSION_CANNOT_DELETE_ACTIVE("1202", "Cannot delete active version"),

    // Import/Export Errors (1300-1399)
    IMPORT_FAILED("1300", "Configuration import failed"),
    EXPORT_FAILED("1301", "Configuration export failed"),
    INVALID_FORMAT("1302", "Invalid configuration format"),

    // Database Errors (1400-1499)
    OPTIMISTIC_LOCK_FAILED("1400", "Concurrent modification detected"),
    DATA_INTEGRITY_VIOLATION("1401", "Data integrity violation"),
    DATABASE_ERROR("1402", "Database operation failed"),

    // Validation Errors (1500-1599)
    INVALID_INPUT("1500", "Invalid input"),
    MISSING_REQUIRED_FIELD("1501", "Missing required field"),
    INVALID_FIELD_VALUE("1502", "Invalid field value"),

    // Generic Errors (9000-9999)
    INTERNAL_SERVER_ERROR("9000", "Internal server error"),
    UNKNOWN_ERROR("9999", "Unknown error");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getFullCode() {
        return "ERR_" + code;
    }
}
