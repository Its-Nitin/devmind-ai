package com.devmind.indexing.internal.domain;

import com.devmind.indexing.ParseStatus;
import com.devmind.indexing.ParsedFile;
import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.SymbolKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Cuts a file into chunks (M2 step 6.2, architecture §7.1):
 *
 * <table>
 *   <caption>Chunking rules</caption>
 *   <tr><th>Input</th><th>Chunks</th></tr>
 *   <tr><td>method or constructor of at most 120 lines</td><td>one, the member's range</td></tr>
 *   <tr><td>longer method or constructor</td><td>80-line windows overlapping by 10, within the member</td></tr>
 *   <tr><td>type</td><td>a header chunk: the type's own lines, with each method, constructor and nested type
 *       reduced to its signature</td></tr>
 *   <tr><td>type under 60 lines</td><td>also one whole-type chunk, unless it would repeat the header chunk</td></tr>
 *   <tr><td>text, or Java that failed to parse or declares nothing</td><td>60-line windows overlapping by 10</td></tr>
 * </table>
 *
 * Fields get no chunk of their own: they're in their type's header chunk.
 */
public final class SymbolChunker {

    /** Bump whenever the rules change; part of the index config hash (M2 step 7). */
    public static final String VERSION = "chunk-1";

    static final int MAX_MEMBER_LINES = 120;
    static final int MEMBER_WINDOW = 80;
    static final int TEXT_WINDOW = 60;
    static final int OVERLAP = 10;
    static final int SMALL_TYPE_LINES = 60;

    private static final String SEPARATOR = " · ";
    private static final String ELIDED = " …";

    private SymbolChunker() {}

    /**
     * Chunks one file.
     *
     * @param content normalized text (LF line endings)
     * @param parsed the parser's result for Java files; null for files no parser handles
     */
    public static List<Chunk> chunk(String path, String content, @Nullable ParsedFile parsed) {
        List<String> lines = SourceLines.of(content);
        if (parsed == null
                || parsed.status() != ParseStatus.OK
                || parsed.symbols().isEmpty()) {
            return textWindows(path, lines);
        }
        return new FileChunker(path, lines, parsed.symbols()).chunks();
    }

    /** {@code 60-line windows overlapping by 10}, for any text. */
    private static List<Chunk> textWindows(String path, List<String> lines) {
        return windows(1, lines.size(), TEXT_WINDOW).stream()
                .map(range -> chunk(range.start(), range.end(), null, path, slice(lines, range.start(), range.end())))
                .toList();
    }

    /**
     * Windows of {@code size} lines over {@code start..end}, each overlapping the previous by {@link #OVERLAP}; the
     * last ends exactly at {@code end}. An empty range has none.
     */
    static List<LineRange> windows(int start, int end, int size) {
        List<LineRange> windows = new ArrayList<>();
        for (int from = start; from <= end; from += size - OVERLAP) {
            int to = Math.min(from + size - 1, end);
            windows.add(new LineRange(from, to));
            if (to == end) {
                break;
            }
        }
        return windows;
    }

    /** Inclusive, 1-based. */
    record LineRange(int start, int end) {}

    private static Chunk chunk(int start, int end, @Nullable Integer symbol, String header, String content) {
        String terms = IdentifierSplitter.terms(content);
        String searchText = Stream.of(header, content, terms)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining("\n"));
        return new Chunk(start, end, symbol, header, content, searchText, (content.length() + 3) / 4);
    }

    private static String slice(List<String> lines, int start, int end) {
        return String.join("\n", lines.subList(start - 1, end));
    }

    /** One parsed file's chunks, in symbol order. */
    private static final class FileChunker {

        private final String path;
        private final List<String> lines;
        private final List<ParsedSymbol> symbols;
        private final List<Chunk> chunks = new ArrayList<>();

        FileChunker(String path, List<String> lines, List<ParsedSymbol> symbols) {
            this.path = path;
            this.lines = lines;
            this.symbols = symbols;
        }

        List<Chunk> chunks() {
            for (int index = 0; index < symbols.size(); index++) {
                ParsedSymbol symbol = symbols.get(index);
                requireWithinFile(symbol);
                switch (symbol.kind()) {
                    case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> type(index, symbol);
                    case METHOD, CONSTRUCTOR -> member(index, symbol);
                    case FIELD -> {
                        // part of its type's header chunk
                    }
                }
            }
            return List.copyOf(chunks);
        }

        private void type(int index, ParsedSymbol type) {
            String header = header(type, null);
            String headerContent = typeHeaderContent(index, type);
            chunks.add(chunk(type.startLine(), type.endLine(), index, header, headerContent));
            String whole = slice(lines, type.startLine(), type.endLine());
            // A small type gets a whole-type chunk too, unless nothing was elided and it would repeat the header chunk.
            if (type.endLine() - type.startLine() + 1 < SMALL_TYPE_LINES && !whole.equals(headerContent)) {
                chunks.add(chunk(type.startLine(), type.endLine(), index, header, whole));
            }
        }

        private void member(int index, ParsedSymbol member) {
            String header = header(parentOf(member), member);
            if (member.endLine() - member.startLine() + 1 <= MAX_MEMBER_LINES) {
                chunks.add(chunk(
                        member.startLine(),
                        member.endLine(),
                        index,
                        header,
                        slice(lines, member.startLine(), member.endLine())));
                return;
            }
            for (LineRange window : windows(member.startLine(), member.endLine(), MEMBER_WINDOW)) {
                chunks.add(
                        chunk(window.start(), window.end(), index, header, slice(lines, window.start(), window.end())));
            }
        }

        /**
         * The type's own lines (declaration, annotations, fields, closing brace) with each multi-line method,
         * constructor or nested type replaced by one line: its signature, indented as written. Those have chunks of
         * their own; here they show the type's shape.
         */
        private String typeHeaderContent(int typeIndex, ParsedSymbol type) {
            List<String> content = new ArrayList<>();
            int next = type.startLine();
            for (int index = typeIndex + 1; index < symbols.size(); index++) {
                ParsedSymbol child = symbols.get(index);
                if (!Objects.equals(child.parentIndex(), typeIndex) || child.kind() == SymbolKind.FIELD) {
                    continue;
                }
                content.addAll(lines.subList(next - 1, child.startLine() - 1));
                if (child.startLine() == child.endLine()) {
                    content.add(lines.get(child.startLine() - 1)); // e.g. an abstract method: nothing to elide
                } else {
                    content.add(indentOf(child) + Objects.requireNonNullElse(child.signature(), child.name()) + ELIDED);
                }
                next = child.endLine() + 1;
            }
            content.addAll(lines.subList(next - 1, type.endLine()));
            return String.join("\n", content);
        }

        private String indentOf(ParsedSymbol symbol) {
            String line = lines.get(symbol.startLine() - 1);
            return line.substring(0, line.length() - line.stripLeading().length());
        }

        /** {@code path · package · type signature · member signature}, leaving out what's empty. */
        private String header(ParsedSymbol type, @Nullable ParsedSymbol member) {
            return Stream.of(path, packageOf(type), type.signature(), member == null ? null : member.signature())
                    .filter(part -> part != null && !part.isEmpty())
                    .collect(Collectors.joining(SEPARATOR));
        }

        private ParsedSymbol parentOf(ParsedSymbol member) {
            Integer parent = member.parentIndex();
            if (parent == null) {
                throw new IllegalArgumentException("member " + member.qualifiedName() + " has no enclosing type");
            }
            return symbols.get(parent);
        }

        /** The package, from the outermost type's qualified name; empty for the default package. */
        private String packageOf(ParsedSymbol type) {
            ParsedSymbol outermost = type;
            for (Integer parent = type.parentIndex(); parent != null; parent = outermost.parentIndex()) {
                outermost = symbols.get(parent);
            }
            String qualifiedName = outermost.qualifiedName();
            int nameStart = qualifiedName.length() - outermost.name().length();
            return nameStart > 0 ? qualifiedName.substring(0, nameStart - 1) : "";
        }

        private void requireWithinFile(ParsedSymbol symbol) {
            if (symbol.endLine() > lines.size()) {
                throw new IllegalArgumentException(
                        symbol.qualifiedName() + " ends at line " + symbol.endLine() + " of " + lines.size());
            }
        }
    }
}
