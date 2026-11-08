package com.devmind.shared;

import java.io.Serial;

/**
 * The resource doesn't exist <em>or the caller may not see it</em>; both map to 404 so existence isn't revealed
 * (API-8). Mapped to a 404 ProblemDetail.
 */
public class NotFoundException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** See {@link DomainException#DomainException(String, String)}. */
    public NotFoundException(String code, String detail) {
        super(code, detail);
    }
}
