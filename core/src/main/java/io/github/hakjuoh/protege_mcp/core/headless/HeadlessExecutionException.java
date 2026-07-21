package io.github.hakjuoh.protege_mcp.core.headless;

import java.util.Map;

import io.github.hakjuoh.protege_mcp.contracts.ContractRedactor;

/** Stable typed failure raised by the headless application-service boundary. */
public final class HeadlessExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String code;
    private final Map<String, Object> details;
    private final boolean retryable;

    public HeadlessExecutionException(String code, String message,
            Map<String, Object> details, boolean retryable, Throwable cause) {
        super(ContractRedactor.sanitize(message), cause);
        this.code = code;
        @SuppressWarnings("unchecked")
        Map<String, Object> safe = (Map<String, Object>) ContractRedactor.redact(details);
        this.details = safe;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public boolean retryable() {
        return retryable;
    }
}
