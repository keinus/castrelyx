package org.keinus.logparser.interfaces.exception;

import java.util.List;

public class InvalidAdapterTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String adapterType;
    private final String providedType;
    @SuppressWarnings("serial")
    private final List<String> validTypes;

    public InvalidAdapterTypeException(String adapterType, String providedType, List<String> validTypes) {
        super(String.format("Invalid %s type '%s'. Valid types are: %s",
                adapterType, providedType, String.join(", ", validTypes)));
        this.adapterType = adapterType;
        this.providedType = providedType;
        this.validTypes = validTypes;
    }

    public InvalidAdapterTypeException(String message) {
        super(message);
        this.adapterType = null;
        this.providedType = null;
        this.validTypes = List.of();
    }

    public String getAdapterType() {
        return adapterType;
    }

    public String getProvidedType() {
        return providedType;
    }

    public List<String> getValidTypes() {
        return validTypes;
    }
}
