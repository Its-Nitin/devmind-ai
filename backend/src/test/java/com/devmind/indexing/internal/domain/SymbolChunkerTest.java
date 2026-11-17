package com.devmind.indexing.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import com.devmind.indexing.ParsedFile;
import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.SymbolKind;
import com.devmind.indexing.internal.domain.SymbolChunker.LineRange;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** M2 step 6.2: chunk boundaries and headers. The golden fixtures are covered by {@code ChunkerGoldenTest}. */
class SymbolChunkerTest {

    @Nested
    class Windows {

        @Test
        void coverARangeWithTenLinesOfOverlapAndEndExactlyAtItsEnd() {
            assertThat(SymbolChunker.windows(1, 60, 60)).containsExactly(new LineRange(1, 60));
            assertThat(SymbolChunker.windows(1, 61, 60)).containsExactly(new LineRange(1, 60), new LineRange(51, 61));
            assertThat(SymbolChunker.windows(1, 110, 60)).containsExactly(new LineRange(1, 60), new LineRange(51, 110));
            assertThat(SymbolChunker.windows(1, 111, 60))
                    .containsExactly(new LineRange(1, 60), new LineRange(51, 110), new LineRange(101, 111));
        }

        @Test
        void handleTinyAndEmptyRanges() {
            assertThat(SymbolChunker.windows(5, 5, 60)).containsExactly(new LineRange(5, 5));
            assertThat(SymbolChunker.windows(1, 0, 60)).isEmpty();
        }
    }

    @Nested
    class Text {

        @Test
        void chunksTextIn60LineWindowsWithThePathAsHeader() {
            // Given
            String content = lines(130);

            // When
            List<Chunk> chunks = SymbolChunker.chunk("docs/notes.md", content, null);

            // Then
            assertThat(chunks)
                    .extracting(Chunk::startLine, Chunk::endLine, Chunk::symbolIndex, Chunk::header)
                    .containsExactly(
                            tuple(1, 60, null, "docs/notes.md"),
                            tuple(51, 110, null, "docs/notes.md"),
                            tuple(101, 130, null, "docs/notes.md"));
            assertThat(chunks.getLast().content()).startsWith("line 101\n").endsWith("line 130");
        }

        @Test
        void fallsBackToTextForJavaThatFailedToParseOrDeclaresNothing() {
            // Given
            String content = lines(3);

            // When / Then
            assertThat(SymbolChunker.chunk("A.java", content, ParsedFile.failed(List.of("line 1, column 1: x"))))
                    .singleElement()
                    .satisfies(chunk -> assertThat(chunk.symbolIndex()).isNull());
            assertThat(SymbolChunker.chunk("package-info.java", content, ParsedFile.parsed(List.of())))
                    .singleElement()
                    .satisfies(chunk -> assertThat(chunk.endLine()).isEqualTo(3));
        }

        @Test
        void givesAnEmptyFileNoChunksAndDoesNotCountAFinalNewlineAsALine() {
            assertThat(SymbolChunker.chunk("empty.md", "", null)).isEmpty();
            assertThat(SymbolChunker.chunk("two.md", "a\nb\n", null))
                    .singleElement()
                    .satisfies(chunk -> assertThat(chunk.endLine()).isEqualTo(2));
        }
    }

    @Nested
    class Members {

        @Test
        void keepsAMemberOfUpTo120LinesWhole() {
            // Given: a class spanning lines 1-122, a method spanning lines 2-121 (120 lines)
            List<ParsedSymbol> symbols = List.of(type(1, 122), method(2, 121));

            // When
            List<Chunk> chunks = SymbolChunker.chunk("A.java", lines(122), ParsedFile.parsed(symbols));

            // Then
            assertThat(chunks)
                    .filteredOn(chunk -> Integer.valueOf(1).equals(chunk.symbolIndex()))
                    .extracting(Chunk::startLine, Chunk::endLine)
                    .containsExactly(tuple(2, 121));
        }

        @Test
        void splitsALongerMemberIn80LineWindowsWithinItsRange() {
            // Given: a method spanning lines 2-122 (121 lines)
            List<ParsedSymbol> symbols = List.of(type(1, 123), method(2, 122));

            // When
            List<Chunk> chunks = SymbolChunker.chunk("A.java", lines(123), ParsedFile.parsed(symbols));

            // Then
            assertThat(chunks)
                    .filteredOn(chunk -> Integer.valueOf(1).equals(chunk.symbolIndex()))
                    .extracting(Chunk::startLine, Chunk::endLine)
                    .containsExactly(tuple(2, 81), tuple(72, 122));
        }

        @Test
        void headsMemberChunksWithPathPackageTypeAndMemberSignature() {
            // When
            List<Chunk> chunks =
                    SymbolChunker.chunk("src/A.java", lines(5), ParsedFile.parsed(List.of(type(1, 5), method(2, 4))));

            // Then
            assertThat(chunks.getLast().header()).isEqualTo("src/A.java · com.x · public class A · void run()");
        }
    }

    @Nested
    class Types {

        @Test
        void reducesMultiLineMembersToTheirSignatureInTheTypeHeaderChunk() {
            // Given
            String content = """
                    public class A {
                        int count;

                        void run() {
                            count++;
                        }
                    }""";

            // When
            Chunk header = SymbolChunker.chunk(
                            "A.java", content, ParsedFile.parsed(List.of(type(1, 7), field(2), method(4, 6))))
                    .getFirst();

            // Then
            assertThat(header.content()).isEqualTo("""
                    public class A {
                        int count;

                        void run() …
                    }""");
        }

        @Test
        void addsAWholeTypeChunkOnlyForTypesUnder60Lines() {
            assertThat(SymbolChunker.chunk("A.java", lines(59), ParsedFile.parsed(List.of(type(1, 59), method(2, 58)))))
                    .extracting(Chunk::symbolIndex)
                    .containsExactly(0, 0, 1);
            assertThat(SymbolChunker.chunk("A.java", lines(60), ParsedFile.parsed(List.of(type(1, 60), method(2, 59)))))
                    .extracting(Chunk::symbolIndex)
                    .containsExactly(0, 1);
        }

        @Test
        void skipsTheWholeTypeChunkWhenItWouldRepeatTheHeaderChunk() {
            // Given: nothing to elide
            List<ParsedSymbol> symbols = List.of(type(1, 3), field(2));

            // When / Then: one chunk, not two identical ones; fields get none of their own
            assertThat(SymbolChunker.chunk("A.java", lines(3), ParsedFile.parsed(symbols)))
                    .singleElement()
                    .satisfies(chunk -> assertThat(chunk.content()).isEqualTo(lines(3)));
        }

        @Test
        void rejectsSymbolsBeyondTheEndOfTheFile() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> SymbolChunker.chunk("A.java", lines(3), ParsedFile.parsed(List.of(type(1, 9)))));
        }
    }

    @Test
    void searchTextAddsTheSplitIdentifiersAndTokensAreEstimatedFromCharacters() {
        // When
        Chunk chunk = SymbolChunker.chunk("A.md", "call findOwnerById", null).getFirst();

        // Then
        assertThat(chunk.searchText()).isEqualTo("A.md\ncall findOwnerById\nfind owner by id");
        assertThat(chunk.tokenEstimate()).isEqualTo(5); // ceil(18 / 4)
        assertThat(chunk).asString().doesNotContain("findOwnerById");
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    }

    private static ParsedSymbol type(int start, int end) {
        return symbol(SymbolKind.CLASS, "A", "com.x.A", "public class A", start, end, null);
    }

    private static ParsedSymbol method(int start, int end) {
        return symbol(SymbolKind.METHOD, "run", "com.x.A#run", "void run()", start, end, 0);
    }

    private static ParsedSymbol field(int line) {
        return symbol(SymbolKind.FIELD, "count", "com.x.A#count", "int count", line, line, 0);
    }

    private static ParsedSymbol symbol(
            SymbolKind kind,
            String name,
            String qualifiedName,
            String signature,
            int start,
            int end,
            @Nullable Integer parent) {
        return new ParsedSymbol(
                kind, name, qualifiedName, signature, qualifiedName, List.of(), List.of(), false, start, end, parent);
    }
}
