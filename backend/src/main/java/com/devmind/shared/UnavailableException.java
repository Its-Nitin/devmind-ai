package com.devmind.shared;

import java.io.Serial;

/**
 * Something the request depends on is temporarily out of reach, e.g. a Git host that timed out. Mapped to a 503
 * ProblemDetail: the same request may succeed later.
 */
public class UnavailableException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** See {@link DomainException#DomainException(String, String)}. */
    public UnavailableException(String code, String detail) {
        super(code, detail);
    }
}
