package com.devmind.indexing;

import java.util.List;
import java.util.Objects;

/**
 * What a {@link CodeParser} found in one file.
 *
 * @param status {@link ParseStatus#OK} or {@link ParseStatus#PARSE_FAILED}
 * @param symbols declarations in source order, each enclosing type before its members; empty when parsing failed
 * @param problems why parsing failed, as positions only ({@code line 3, column 14: syntax error}); never source text,
 *     which could contain secrets that survived redaction (OBS-3). Empty when parsing succeeded
 */
public record ParsedFile(ParseStatus status, List<ParsedSymbol> symbols, List<String> problems) {

    /**
     * @throws IllegalArgumentException if the status is {@code SKIPPED}, a failed file has symbols, a parsed file has
     *     problems, or a parent index doesn't point to an earlier type
     */
    public ParsedFile {
        Objects.requireNonNull(status, "status");
        symbols = List.copyOf(symbols);
        problems = List.copyOf(problems);
        if (status == ParseStatus.SKIPPED) {
            throw new IllegalArgumentException("parsers never skip; SKIPPED is the indexer's");
        }
        if (status == ParseStatus.PARSE_FAILED ? !symbols.isEmpty() : !problems.isEmpty()) {
            throw new IllegalArgumentException("symbols only when parsed, problems only when failed");
        }
        for (int i = 0; i < symbols.size(); i++) {
            Integer parent = symbols.get(i).parentIndex();
            if (parent != null
                    && (parent < 0 || parent >= i || !symbols.get(parent).kind().isType())) {
                throw new IllegalArgumentException("symbol " + i + " must have an earlier type as its parent");
            }
        }
    }

    /** Returns a successfully parsed file. */
    public static ParsedFile parsed(List<ParsedSymbol> symbols) {
        return new ParsedFile(ParseStatus.OK, symbols, List.of());
    }

    /** Returns a file the parser rejected. */
    public static ParsedFile failed(List<String> problems) {
        return new ParsedFile(ParseStatus.PARSE_FAILED, List.of(), problems);
    }
}
