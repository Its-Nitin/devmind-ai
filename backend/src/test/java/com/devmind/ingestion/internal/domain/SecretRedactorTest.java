package com.devmind.ingestion.internal.domain;

import static com.devmind.ingestion.internal.domain.SecretRedactor.MARKER;
import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.ingestion.internal.domain.SecretRedactor.Redacted;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-5, threat T5. Fake secrets only; none of these are real credentials. */
class SecretRedactorTest {

    @Nested
    class PrivateKeys {

        @Test
        void redactsEachBodyLineAndKeepsTheBlockStructure() {
            // Given
            String text = """
                    tls:
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn
                    kQIDAQABAoIBAH3Y0vq9yq2ZzCf8

                    -----END RSA PRIVATE KEY-----
                    after: kept""";

            // When
            Redacted redacted = SecretRedactor.redact(text, "yaml");

            // Then: the same seven lines, the markers kept, the key gone, the blank line still blank
            assertThat(redacted.text()).isEqualTo("""
                    tls:
                    -----BEGIN RSA PRIVATE KEY-----
                    %1$s
                    %1$s

                    -----END RSA PRIVATE KEY-----
                    after: kept""".formatted(MARKER));
            assertThat(redacted.count()).isEqualTo(1);
        }

        @Test
        void redactsAKeyHeldOnOneLineWithEscapedNewlines() {
            // Given: the way JSON service-account files hold keys
            String text = "\"private_key\": \"-----BEGIN PRIVATE KEY-----\\nMIIEvQ\\n-----END PRIVATE KEY-----\\n\",";

            // When
            Redacted redacted = SecretRedactor.redact(text, "json");

            // Then
            assertThat(redacted.text())
                    .isEqualTo("\"private_key\": \"-----BEGIN PRIVATE KEY-----" + MARKER
                            + "-----END PRIVATE KEY-----\\n\",");
            assertThat(redacted.count()).isEqualTo(1);
        }

        @Test
        void redactsToTheEndWhenTheBlockIsNeverClosed() {
            // When
            Redacted redacted =
                    SecretRedactor.redact("-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\nmore", "markdown");

            // Then
            assertThat(redacted.text()).isEqualTo("-----BEGIN OPENSSH PRIVATE KEY-----\n" + MARKER + "\n" + MARKER);
        }

        @Test
        void leavesPublicKeysAndCertificatesAlone() {
            // Given
            String text = "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqh\n-----END PUBLIC KEY-----";

            // When / Then
            assertThat(SecretRedactor.redact(text, "markdown")).isEqualTo(new Redacted(text, 0));
        }
    }

    @Nested
    class Tokens {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "AKIAIOSFODNN7EXAMPLE", // AWS access key ID
                    "ASIAY34FZKBOKMUTVV7A",
                    "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789", // GitHub
                    "gho_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789",
                    "github_pat_11ABCDEFG0aBcDeFgHiJkLmN_opQrStUvWxYz",
                    "xoxb-1234567890-abcdefghij", // Slack
                    "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q" // Google
                })
        void redactsTokensRecognizableByShapeAnywhere(String token) {
            // Given: under a name that isn't secret-like, so only the shape can catch it
            String line = "String id = \"" + token + "\"; // " + token;

            // When
            Redacted redacted = SecretRedactor.redact(line, "java");

            // Then
            assertThat(redacted.text()).isEqualTo("String id = \"" + MARKER + "\"; // " + MARKER);
            assertThat(redacted.count()).isEqualTo(2);
        }

        @ParameterizedTest
        @ValueSource(strings = {"AKIA1234", "ghp_short", "AKIAIOSFODNN7EXAMPLEX", "xoxb-short"})
        void leavesLookAlikesAlone(String almost) {
            String line = "String id = \"" + almost + "\";";
            assertThat(SecretRedactor.redact(line, "java")).isEqualTo(new Redacted(line, 0));
        }
    }

    @Nested
    class Assignments {

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "java|String password = \"hunter2hunter2\";|String password = \"%s\";",
                    "java|private static final String DB_PASSWORD = \"correct-horse\";"
                            + "|private static final String DB_PASSWORD = \"%s\";",
                    "json|\"apiKey\": \"abcdefgh12345\",|\"apiKey\": \"%s\",",
                    "yaml|  client-secret: 'abcdefghijk'|  client-secret: '%s'",
                    "typescript|const token = `x`; const authToken = 'eyJhbGciOiJIUzI1';"
                            + "|const token = `x`; const authToken = '%s';"
                })
        void redactsQuotedValuesOfSecretLikeNamesInAnyLanguage(String language, String line, String expected) {
            // When
            Redacted redacted = SecretRedactor.redact(line, language);

            // Then
            assertThat(redacted.text()).isEqualTo(expected.formatted(MARKER));
            assertThat(redacted.count()).isEqualTo(1);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "String password = \"short\";", // under 8 characters
                    "PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();",
                    "String tokenizer = \"whitespace-tokenizer\";", // the name doesn't end in token
                    "log.info(\"password reset requested for {}\", user);",
                    "String password = System.getenv(\"DB_PASSWORD\");"
                })
        void leavesCodeThatOnlyLooksSecretAlone(String line) {
            assertThat(SecretRedactor.redact(line, "java")).isEqualTo(new Redacted(line, 0));
        }

        @Test
        void countsATokenUnderASecretLikeNameOnce() {
            // When
            Redacted redacted = SecretRedactor.redact("token = \"ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789\"", "java");

            // Then
            assertThat(redacted).isEqualTo(new Redacted("token = \"" + MARKER + "\"", 1));
        }
    }

    @Nested
    class ConfigFiles {

        @ParameterizedTest
        @CsvSource(
                delimiter = '|',
                value = {
                    "properties|spring.datasource.password=hunter2hunter2|spring.datasource.password=%s",
                    "properties|app.api-key = 0123456789abcdef|app.api-key = %s",
                    "yaml|    password: s3cr3tvalue # prod only|    password: %s # prod only",
                    "yaml|  jwt.secret: c2VjcmV0LWtleS1mb3ItdGVzdHM=|  jwt.secret: %s"
                })
        void redactsUnquotedValuesOfKeysEndingInASecretLikeName(String language, String line, String expected) {
            assertThat(SecretRedactor.redact(line, language)).isEqualTo(new Redacted(expected.formatted(MARKER), 1));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "spring.datasource.password=${DB_PASSWORD}", // a placeholder, not a secret
                    "spring.datasource.password={cipher}AQB1",
                    "github.token=https://example.com/oauth/token", // a URL
                    "token-uri=https://oauth2.example.com/token", // the key doesn't end in token
                    "password=short",
                    "password="
                })
        void leavesPlaceholdersUrlsAndShortValuesAlone(String line) {
            assertThat(SecretRedactor.redact(line, "properties")).isEqualTo(new Redacted(line, 0));
        }

        @Test
        void appliesTheUnquotedRuleToConfigFilesOnly() {
            // Given: the same line is ordinary code-like text in Java or Markdown
            String line = "password=hunter2hunter2";

            // When / Then
            assertThat(SecretRedactor.redact(line, "java").count()).isZero();
            assertThat(SecretRedactor.redact(line, "markdown").count()).isZero();
            assertThat(SecretRedactor.redact(line, "properties").count()).isEqualTo(1);
        }
    }

    @Test
    void keepsTheLineCountAndCountsEverySecret() {
        // Given
        String text = """
                db.password=hunter2hunter2
                aws.id=AKIAIOSFODNN7EXAMPLE

                ok=true
                """;

        // When
        Redacted redacted = SecretRedactor.redact(text, "properties");

        // Then
        assertThat(redacted.text().split("\n", -1)).hasSameSizeAs(text.split("\n", -1));
        assertThat(redacted.text()).endsWith("\n\nok=true\n");
        assertThat(redacted.count()).isEqualTo(2);
    }
}
