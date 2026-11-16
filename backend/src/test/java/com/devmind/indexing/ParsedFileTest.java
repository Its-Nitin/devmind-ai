package com.devmind.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ParsedFileTest {

    @Test
    void acceptsMembersWhoseParentIsAnEarlierType() {
        assertThat(ParsedFile.parsed(List.of(type(null), method(0))).symbols()).hasSize(2);
    }

    @Test
    void rejectsParentsThatAreLaterMissingOrNotTypes() {
        assertThatIllegalArgumentException().isThrownBy(() -> ParsedFile.parsed(List.of(method(1), type(null))));
        assertThatIllegalArgumentException().isThrownBy(() -> ParsedFile.parsed(List.of(type(null), method(5))));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ParsedFile.parsed(List.of(type(null), method(0), method(1))));
    }

    @Test
    void keepsSymbolsAndProblemsApartByStatus() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParsedFile(ParseStatus.PARSE_FAILED, List.of(type(null)), List.of("x")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParsedFile(ParseStatus.OK, List.of(), List.of("line 1, column 1: syntax error")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParsedFile(ParseStatus.SKIPPED, List.of(), List.of()));
    }

    @Test
    void symbolsRejectInvalidLineRangesAndCopyTheirLists() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParsedSymbol(
                        SymbolKind.CLASS, "A", "A", null, "A", List.of(), List.of(), false, 5, 4, null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ParsedSymbol(
                        SymbolKind.CLASS, "A", "A", null, "A", List.of(), List.of(), false, 0, 1, null));

        List<String> modifiers = new ArrayList<>(List.of("public"));
        ParsedSymbol symbol =
                new ParsedSymbol(SymbolKind.CLASS, "A", "A", null, "A", modifiers, List.of(), false, 1, 1, null);
        modifiers.add("final");
        assertThat(symbol.modifiers()).containsExactly("public");
    }

    @Test
    void knowsWhichKindsAreTypes() {
        assertThat(List.of(SymbolKind.values()))
                .filteredOn(SymbolKind::isType)
                .containsExactly(
                        SymbolKind.CLASS,
                        SymbolKind.INTERFACE,
                        SymbolKind.ENUM,
                        SymbolKind.RECORD,
                        SymbolKind.ANNOTATION);
    }

    private static ParsedSymbol type(@Nullable Integer parent) {
        return new ParsedSymbol(
                SymbolKind.CLASS, "A", "p.A", "class A", "p.A", List.of(), List.of(), false, 1, 9, parent);
    }

    private static ParsedSymbol method(@Nullable Integer parent) {
        return new ParsedSymbol(
                SymbolKind.METHOD, "m", "p.A#m", "void m()", "p.A#m()", List.of(), List.of(), false, 2, 3, parent);
    }
}
