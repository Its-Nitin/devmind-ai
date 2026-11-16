package com.devmind.ingestion.internal.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Which paths are indexed, judged by path alone: the deny-list, secret-file patterns and extension allow-list. */
public final class PathRules {

    /** Build output, dependencies, IDE state and generated code, at any depth (M2 step 4, row 1). */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "target",
            "build",
            "out",
            "node_modules",
            "dist",
            ".git",
            ".idea",
            ".gradle",
            "generated",
            "generated-sources");

    /**
     * A Java or Kotlin source root. Directories below it are packages, not build output: {@code com/acme/build} is
     * code. So the deny-list only applies to the path up to and including it.
     */
    private static final Pattern SOURCE_ROOT = Pattern.compile("(^|/)src/[^/]+/(java|kotlin)/");

    /** File names that typically hold secrets (row 2); matched case-sensitively, like the files themselves. */
    private static final List<Pattern> SECRET_FILE_NAMES =
            List.of(Pattern.compile("\\.env.*"), Pattern.compile("id_rsa.*"), Pattern.compile("id_ed25519.*"));

    /**
     * {@code credentials*} names data files (credentials.json, credentials.yml) but also code such as {@code
     * credentials.service.ts}, so code files are exempt from this one pattern.
     */
    private static final Pattern CREDENTIALS_FILE = Pattern.compile("credentials.*");

    private static final Set<String> CODE_LANGUAGES = Set.of("java", "kotlin", "typescript");

    /** Extensions of key stores and state files that hold secrets; compared in lowercase. */
    private static final Set<String> SECRET_EXTENSIONS = Set.of("pem", "key", "p12", "jks", "keystore", "tfstate");

    /** The extension allow-list (row 5) and the language each maps to. Only {@code java} is parsed (step 5). */
    private static final Map<String, String> LANGUAGES = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("kt", "kotlin"),
            Map.entry("kts", "kotlin"),
            Map.entry("xml", "xml"),
            Map.entry("yml", "yaml"),
            Map.entry("yaml", "yaml"),
            Map.entry("properties", "properties"),
            Map.entry("sql", "sql"),
            Map.entry("md", "markdown"),
            Map.entry("gradle", "gradle"),
            Map.entry("json", "json"),
            Map.entry("ts", "typescript"),
            Map.entry("html", "html"),
            Map.entry("scss", "scss"));

    private PathRules() {}

    /** Returns whether a directory of {@code path} is on the deny-list, ignoring package directories. */
    public static boolean inExcludedDirectory(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return false;
        }
        // Up to and including the source root, whose source-set name counts (src/generated/java is excluded).
        Matcher sourceRoot = SOURCE_ROOT.matcher(path);
        String directories = sourceRoot.find() ? path.substring(0, sourceRoot.end()) : path.substring(0, lastSlash);
        for (String segment : directories.split("/", -1)) {
            if (EXCLUDED_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** Returns whether {@code path} names a file that typically holds secrets. */
    public static boolean isSecretFile(String path) {
        String name = fileName(path);
        if (SECRET_EXTENSIONS.contains(extension(name))) {
            return true;
        }
        if (SECRET_FILE_NAMES.stream().anyMatch(pattern -> pattern.matcher(name).matches())) {
            return true;
        }
        return CREDENTIALS_FILE.matcher(name).matches()
                && language(path).filter(CODE_LANGUAGES::contains).isEmpty();
    }

    /** Returns the language of an allow-listed extension, or empty if the extension isn't allowed. */
    public static Optional<String> language(String path) {
        return Optional.ofNullable(LANGUAGES.get(extension(fileName(path))));
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Lowercase text after the last dot; empty for none, and for dotfiles such as {@code .gitignore}. */
    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
