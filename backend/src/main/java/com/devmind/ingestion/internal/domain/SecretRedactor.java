package com.devmind.ingestion.internal.domain;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces secrets in normalized text with {@link #MARKER} before anything is stored or embedded (SEC-5, threat T5;
 * M2 step 4, row 8). Works line by line and never adds or removes a line, so line numbers stay true to the commit.
 *
 * <p>Detectors: private-key blocks; AWS access key IDs, GitHub, Slack and Google API tokens; quoted values assigned to
 * a secret-like name in any language ({@code password = "..."}, {@code "apiKey": "..."}); and, in properties and YAML
 * files only, unquoted values of keys ending in such a name ({@code spring.datasource.password=...}). Only the value
 * is replaced, so the surrounding code still reads sensibly.
 *
 * <p>No entropy check: on source code it flags hashes, test fixtures and IDs far more often than secrets.
 */
public final class SecretRedactor {

    /** What a secret is replaced with. */
    public static final String MARKER = "«REDACTED»";

    /**
     * Redacted text.
     *
     * @param text the input with secrets replaced; same number of lines
     * @param count secrets replaced; a private-key block counts once
     */
    public record Redacted(String text, int count) {}

    private static final Pattern KEY_BEGIN = Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----");
    private static final Pattern KEY_END = Pattern.compile("-----END [A-Z0-9 ]*PRIVATE KEY( BLOCK)?-----");

    /** Tokens recognizable by shape alone; the whole match is replaced. */
    private static final List<Pattern> TOKENS = List.of(
            Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b"), // AWS access key ID
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b"), // GitHub token
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{22,255}\\b"), // GitHub fine-grained token
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}"), // Slack token
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}")); // Google API key

    private static final String SECRET_NAME = "(password|passwd|secret|token|api[_-]?key)";
    private static final String NOT_ALREADY_REDACTED = "(?!" + Pattern.quote(MARKER) + ")";

    /** A quoted value of 8+ characters assigned to a secret-like name; group 3 (the value) is replaced. */
    private static final Pattern QUOTED_ASSIGNMENT = Pattern.compile(
            "(?i)" + SECRET_NAME + "['\"]?\\s*[:=]\\s*(['\"])" + NOT_ALREADY_REDACTED + "([^'\"\\n]{8,})\\2");

    /**
     * In properties and YAML, an unquoted value of 8+ characters for a key ending in a secret-like name. Placeholders
     * ({@code ${DB_PASSWORD}}) and URLs aren't secrets and are left alone. Group 2 (the value) is replaced.
     */
    private static final Pattern CONFIG_ASSIGNMENT = Pattern.compile("(?i)^\\s*[\\w.-]*" + SECRET_NAME + "\\s*[:=]\\s*"
            + "(?!https?://)" + NOT_ALREADY_REDACTED + "([^\\s'\"#${][^\\s#]{7,})\\s*(#.*)?$");

    private static final Set<String> CONFIG_LANGUAGES = Set.of("properties", "yaml");

    private SecretRedactor() {}

    /** Returns {@code text} with every detected secret replaced; {@code language} picks the config-file detector. */
    public static Redacted redact(String text, String language) {
        Run run = new Run(CONFIG_LANGUAGES.contains(language));
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            lines[i] = run.line(lines[i]);
        }
        return new Redacted(String.join("\n", lines), run.count);
    }

    /** One pass over one text: whether it's inside a key block, and how much was replaced. */
    private static final class Run {

        private final boolean configFile;
        private boolean inKeyBlock;
        private int count;

        Run(boolean configFile) {
            this.configFile = configFile;
        }

        String line(String line) {
            if (inKeyBlock) {
                return keyBlockLine(line);
            }
            Matcher begin = KEY_BEGIN.matcher(line);
            if (begin.find()) {
                return keyBlockStart(line, begin.end());
            }
            String result = line;
            for (Pattern token : TOKENS) {
                result = replace(result, token, 0);
            }
            result = replace(result, QUOTED_ASSIGNMENT, 3);
            return configFile ? replace(result, CONFIG_ASSIGNMENT, 2) : result;
        }

        /** The BEGIN line stays; what follows it on the line is key material, unless the key ends on this line too. */
        private String keyBlockStart(String line, int afterBegin) {
            count++;
            Matcher end = KEY_END.matcher(line);
            if (end.find(afterBegin)) {
                return line.substring(0, afterBegin) + MARKER + line.substring(end.start()); // e.g. a JSON string
            }
            inKeyBlock = true;
            return line.substring(afterBegin).isBlank() ? line : line.substring(0, afterBegin) + MARKER;
        }

        /** Body lines become the marker (blank ones stay blank); the END line stays. Unterminated runs to the end. */
        private String keyBlockLine(String line) {
            Matcher end = KEY_END.matcher(line);
            if (end.find()) {
                inKeyBlock = false;
                return end.start() == 0 ? line : MARKER + line.substring(end.start());
            }
            return line.isBlank() ? line : MARKER;
        }

        /** Replaces {@code group} of every match of {@code pattern} (0 for the whole match). */
        private String replace(String line, Pattern pattern, int group) {
            Matcher matcher = pattern.matcher(line);
            StringBuilder out = new StringBuilder();
            int copied = 0;
            while (matcher.find()) {
                out.append(line, copied, matcher.start(group)).append(MARKER);
                copied = matcher.end(group);
                count++;
            }
            return copied == 0 ? line : out.append(line, copied, line.length()).toString();
        }
    }
}
