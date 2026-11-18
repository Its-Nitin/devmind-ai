package com.devmind.shared;

import java.io.Serial;

/**
 * The request is well-formed but can't be acted on as it stands, e.g. a ref that names no commit. Mapped to a 422
 * ProblemDetail. Retrying the same request won't help; changing it might.
 */
public class UnprocessableException extends DomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** See {@link DomainException#DomainException(String, String)}. */
    public UnprocessableException(String code, String detail) {
        super(code, detail);
    }
}
