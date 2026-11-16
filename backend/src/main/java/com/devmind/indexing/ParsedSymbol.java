package com.devmind.indexing;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One declaration found by a {@link CodeParser}.
 *
 * @param kind what it declares
 * @param name simple name, e.g. {@code findById}; a constructor has its class's name
 * @param qualifiedName {@code pkg.Outer.Inner} for types, {@code pkg.Outer#member} for members
 * @param signature the declaration header as written, e.g. {@code public Owner findById(int id)}; null if none applies
 * @param symbolKey stable identity across revisions (ADR-0003): the qualified name, plus {@code (erased,param,types)}
 *     for methods and constructors so overloads differ, e.g. {@code com.x.OwnerService#findById(int)}
 * @param modifiers as written, e.g. {@code [public, static]}
 * @param annotations full source text of each annotation, e.g. {@code @GetMapping("/owners/{ownerId}")}
 * @param test whether this is test code: under {@code src/test/}, or it or an enclosing type carries a test annotation
 * @param startLine first line, 1-based, including its Javadoc and annotations
 * @param endLine last line, inclusive
 * @param parentIndex index in {@link ParsedFile#symbols()} of the enclosing type; null for top-level types
 */
public record ParsedSymbol(
        SymbolKind kind,
        String name,
        String qualifiedName,
        @Nullable String signature,
        String symbolKey,
        List<String> modifiers,
        List<String> annotations,
        boolean test,
        int startLine,
        int endLine,
        @Nullable Integer parentIndex) {

    /** @throws IllegalArgumentException if the line range is invalid or a name is empty */
    public ParsedSymbol {
        Objects.requireNonNull(kind, "kind");
        if (name.isEmpty() || qualifiedName.isEmpty() || symbolKey.isEmpty()) {
            throw new IllegalArgumentException("names must not be empty");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid line range " + startLine + "-" + endLine);
        }
        modifiers = List.copyOf(modifiers);
        annotations = List.copyOf(annotations);
    }
}
