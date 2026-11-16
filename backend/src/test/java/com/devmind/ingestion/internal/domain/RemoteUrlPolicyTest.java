package com.devmind.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-3 URL rules, threat T2. */
class RemoteUrlPolicyTest {

    private final RemoteUrlPolicy policy = new RemoteUrlPolicy(List.of("github.com", "gitlab.com"));

    @ParameterizedTest
    @CsvSource({
        "https://github.com/spring-projects/spring-petclinic, https://github.com/spring-projects/spring-petclinic",
        "https://gitlab.com/owner/repo.git,                   https://gitlab.com/owner/repo.git",
        "https://GitHub.com/owner/my_repo.v2,                 https://github.com/owner/my_repo.v2",
        "HTTPS://github.com/owner/repo,                       https://github.com/owner/repo"
    })
    void acceptsHttpsRepositoryUrlsOnAllowedHostsAndNormalizesThem(String url, String expected) {
        assertThat(policy.validate(url)).contains(URI.create(expected));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://github.com/owner/repo", // not HTTPS
                "ssh://git@github.com/owner/repo.git",
                "git@github.com:owner/repo.git", // scp-like syntax
                "git://github.com/owner/repo",
                "file:///srv/repos/repo.git",
                "https://user@github.com/owner/repo", // credentials in the URL
                "https://user:token@github.com/owner/repo",
                "https://github.com:443/owner/repo", // explicit port
                "https://github.com:8443/owner/repo",
                "https://140.82.112.3/owner/repo", // IP literals
                "https://[::1]/owner/repo",
                "https://2130706433/owner/repo",
                "https://localhost/owner/repo",
                "https://evil.example/owner/repo", // host not allowed
                "https://github.com.evil.example/owner/repo",
                "https://api.github.com/owner/repo", // subdomains aren't the allowed host
                "https://evil.example#@github.com/owner/repo",
                "https://github.com\\@evil.example/owner/repo",
                "https://github.com/owner", // not owner/repo
                "https://github.com/owner/repo/tree/main",
                "https://github.com/",
                "https://github.com/owner/repo?ref=main", // query
                "https://github.com/owner/repo#readme", // fragment
                "https://github.com/%2e%2e/repo", // percent-encoding
                "https://github.com/../repo", // dot segments
                "https://github.com/owner/..",
                "https://github.com/owner/repo name",
                "github.com/owner/repo", // no scheme
                ""
            })
    void rejectsUrlsThatBreakTheRules(String url) {
        assertThat(policy.validate(url)).isEmpty();
    }

    @Test
    void rejectsUrlsLongerThanTheLimit() {
        // Given
        String base = "https://github.com/owner/";
        String atLimit = base + "r".repeat(RemoteUrlPolicy.MAX_LENGTH - base.length());

        // When / Then
        assertThat(policy.validate(atLimit)).isPresent();
        assertThat(policy.validate(atLimit + "r")).isEmpty();
    }

    @Test
    void rejectsEverythingWhenNoHostIsAllowed() {
        assertThat(new RemoteUrlPolicy(List.of()).validate("https://github.com/owner/repo"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "GitHub.com", "localhost", "github.com.", "*.github.com", ""})
    void refusesAllowListEntriesThatAreNotLowercaseDnsNames(String host) {
        assertThatIllegalArgumentException().isThrownBy(() -> new RemoteUrlPolicy(List.of(host)));
    }
}
