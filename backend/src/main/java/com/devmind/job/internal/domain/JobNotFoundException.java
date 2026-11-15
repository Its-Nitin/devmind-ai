package com.devmind.job.internal.domain;

import com.devmind.shared.NotFoundException;
import java.io.Serial;

/** The job doesn't exist or belongs to someone else's project; deliberately indistinguishable (API-8). */
public class JobNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JobNotFoundException() {
        super("job-not-found", "Job not found.");
    }
}
