package com.devmind.indexing.internal.domain;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Splits code identifiers into lowercase words, so a lexical search for "owner by id" finds {@code findOwnerById}
 * (M2 step 6.1). Indexing applies it to chunk text; M3 must apply the same splitter to queries, so it has to become
 * part of the indexing API then.
 *
 * <ul>
 *   <li>{@code findOwnerById} → {@code find owner by id}
 *   <li>{@code HTTPServerError} → {@code http server error}
 *   <li>{@code OWNER_ID} → {@code owner id}
 *   <li>{@code parseXML2Json} → {@code parse xml 2 json}
 *   <li>{@code owner-details} → {@code owner details}
 * </ul>
 */
public final class IdentifierSplitter {

    /** Anything that isn't a letter or digit separates words: {@code _}, {@code -}, {@code .}, {@code $}, ... */
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Word boundaries inside a run of letters and digits: lower→upper ({@code findO}), the end of an acronym before a
     * capitalized word ({@code HTTPServer} splits before {@code S}), and letter↔digit.
     */
    private static final Pattern BOUNDARIES = Pattern.compile(
            "(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");

    /** Identifier-like tokens in text, including kebab-case. */
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(-[\\p{L}\\p{N}_$]+)*");

    private IdentifierSplitter() {}

    /** Returns the words of {@code identifier}, lowercase and space-separated; empty if it has none. */
    public static String split(String identifier) {
        StringBuilder words = new StringBuilder();
        for (String part : SEPARATORS.split(identifier, -1)) {
            for (String word : BOUNDARIES.split(part, -1)) {
                if (!word.isEmpty()) {
                    words.append(words.isEmpty() ? "" : " ").append(word.toLowerCase(Locale.ROOT));
                }
            }
        }
        return words.toString();
    }

    /**
     * Returns the split forms of the identifiers in {@code text} that split into something new, each once, in order
     * of first appearance: {@code ownerService.findById(id)} gives {@code owner service find by id}. Identifiers that
     * are a single word in any case ({@code id}, {@code Owner}) add nothing.
     */
    public static String terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = IDENTIFIER.matcher(text);
        while (matcher.find()) {
            String identifier = matcher.group();
            String words = split(identifier);
            // "Owner" adds nothing: to_tsvector('simple', ...) lowercases on its own.
            if (!words.isEmpty() && !words.equals(identifier.toLowerCase(Locale.ROOT))) {
                terms.add(words);
            }
        }
        return terms.stream().collect(Collectors.joining(" "));
    }
}
