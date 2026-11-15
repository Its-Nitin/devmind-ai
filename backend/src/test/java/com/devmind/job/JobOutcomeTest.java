package com.devmind.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Failure codes and details are stored and shown to users, so they are checked on construction (ERR-5). */
class JobOutcomeTest {

    @Test
    void acceptsKebabCaseCodeAndShortDetail() {
        JobOutcome outcome = JobOutcome.retryable("clone-timeout", "Cloning took too long.");

        assertThat(outcome).isEqualTo(new JobOutcome.RetryableFailure("clone-timeout", "Cloning took too long."));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Clone-Timeout", "clone_timeout", "-clone", "clone-", "clone timeout"})
    void rejectsCodesThatAreNotKebabCase(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> JobOutcome.permanent(code, "Detail."));
    }

    @Test
    void rejectsBlankOrOverlongDetail() {
        String tooLong = "x".repeat(JobOutcome.MAX_DETAIL_LENGTH + 1);

        assertThatIllegalArgumentException().isThrownBy(() -> JobOutcome.permanent("code", " "));
        assertThatIllegalArgumentException().isThrownBy(() -> JobOutcome.retryable("code", tooLong));
    }
}
