package com.devmind.ai;

import com.devmind.shared.UnavailableException;
import java.io.Serial;

/**
 * The embedding model couldn't produce vectors right now: the server is unreachable or timed out, the configured
 * model isn't installed, or the response was unusable. Retrying later may succeed, so jobs treat it as retryable
 * (ERR-3) and requests map it to 503.
 */
public class EmbeddingUnavailableException extends UnavailableException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The model server can't be reached, timed out or failed. */
    public static final String UNREACHABLE = "embedding-unavailable";

    /** The model server doesn't have the configured model. */
    public static final String MODEL_NOT_FOUND = "embedding-model-not-found";

    /** The model server answered, but not with one vector of the expected length per input. */
    public static final String INVALID_RESPONSE = "embedding-invalid-response";

    /**
     * @param code one of {@link #UNREACHABLE}, {@link #MODEL_NOT_FOUND} or {@link #INVALID_RESPONSE}
     * @param detail user-safe description, without input text (OBS-3)
     */
    public EmbeddingUnavailableException(String code, String detail) {
        super(code, detail);
    }
}
