package com.devmind.shared;

import java.io.Serial;

/**
 * Base for expected domain failures that map to a ProblemDetail (ERR-1). Modules subclass {@link NotFoundException}
 * or {@link ConflictException} with a specific type, e.g. {@code ProjectNotFoundException}.
 *
 * <p>The message becomes the ProblemDetail {@code detail} and is shown to the client: it must be user-safe (no
 * internal IDs, source code or secrets, OBS-3).
 */
public abstract class DomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * @param code stable, kebab-case problem code, e.g. {@code project-not-found}; it becomes the ProblemDetail
     *     {@code type} suffix and {@code code} property (API-3)
     * @param detail user-safe description of this occurrence
     */
    protected DomainException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    /** Returns the stable problem code, never null. */
    public String code() {
        return code;
    }
}
