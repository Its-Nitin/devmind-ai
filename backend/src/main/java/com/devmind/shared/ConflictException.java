package com.devmind.shared;

import java.io.Serial;

/** The request conflicts with current state, e.g. a duplicate project name. Mapped to a 409 ProblemDetail. */
public class ConflictException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** See {@link DomainException#DomainException(String, String)}. */
    public ConflictException(String code, String detail) {
        super(code, detail);
    }
}
