package com.devmind.indexing.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.devmind.indexing.ParseStatus;
import com.devmind.indexing.ParsedFile;
import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.SymbolKind;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Behaviour the golden snapshots don't show on their own (M2 step 5). */
class JavaParserCodeParserTest {

    private final JavaParserCodeParser parser = new JavaParserCodeParser();

    @Test
    void supportsJavaOnlyAndHasAVersion() {
        assertThat(parser.supports("java")).isTrue();
        assertThat(parser.supports("kotlin")).isFalse();
        assertThat(parser.version()).isEqualTo("java-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/test/java/com/x/Helper.java", "service/src/test/java/com/x/Helper.java"})
    void marksEverythingUnderSrcTestAsTestCode(String path) {
        // When
        ParsedFile parsed = parser.parse(path, "package com.x;\nclass Helper {\n    void help() {}\n}\n");

        // Then
        assertThat(parsed.symbols())
                .hasSize(2)
                .allSatisfy(symbol -> assertThat(symbol.test()).isTrue());
    }

    @Test
    void keepsMainCodeWithoutTestAnnotationsAsMainCode() {
        assertThat(parser.parse("src/main/java/com/x/Helper.java", "class Helper { void help() {} }")
                        .symbols())
                .noneMatch(ParsedSymbol::test);
    }

    @Test
    void marksOnlyTheAnnotatedMethodInAnUnannotatedClass() {
        // Given
        String source = """
                class Mixed {
                    @org.junit.jupiter.api.Test
                    void checks() {}

                    void helper() {}
                }
                """;

        // When
        List<ParsedSymbol> symbols = parser.parse("Mixed.java", source).symbols();

        // Then: the qualified annotation counts by its simple name
        assertThat(symbols)
                .extracting(ParsedSymbol::name, ParsedSymbol::test)
                .containsExactly(tuple("Mixed", false), tuple("checks", true), tuple("helper", false));
    }

    @Test
    void parsesCurrentJavaSyntax() {
        // Given: sealed types, record patterns, unnamed variables, text blocks
        String source = """
                package com.x;

                sealed interface Shape permits Circle, Square {}

                record Circle(double radius) implements Shape {}

                record Square(double side) implements Shape {}

                class Areas {
                    static double area(Shape shape) {
                        return switch (shape) {
                            case Circle(var radius) -> Math.PI * radius * radius;
                            case Square(double side) -> side * side;
                        };
                    }

                    static int count(java.util.List<String> items) {
                        int n = 0;
                        for (String _ : items) {
                            n++;
                        }
                        String text = \"""
                                multi
                                line\""";
                        return n + text.length();
                    }
                }
                """;

        // When
        ParsedFile parsed = parser.parse("src/main/java/com/x/Shapes.java", source);

        // Then
        assertThat(parsed.status()).isEqualTo(ParseStatus.OK);
        assertThat(parsed.symbols())
                .extracting(ParsedSymbol::symbolKey)
                .contains(
                        "com.x.Shape", "com.x.Circle", "com.x.Areas#area(Shape)", "com.x.Areas#count(java.util.List)");
        assertThat(parsed.symbols().getFirst().signature()).isEqualTo("sealed interface Shape");
    }

    @Test
    void parsesAnEmptyFileToNoSymbols() {
        assertThat(parser.parse("Empty.java", "")).isEqualTo(ParsedFile.parsed(List.of()));
    }

    @Test
    void reportsSyntaxErrorsByPositionOnlyNeverQuotingTheSource() {
        // Given: broken code containing something that must never be copied into an error
        String source = "class Broken {\n    String apiKey = \"sk-not-a-real-key\" +;\n}\n";

        // When
        ParsedFile parsed = parser.parse("Broken.java", source);

        // Then
        assertThat(parsed.status()).isEqualTo(ParseStatus.PARSE_FAILED);
        assertThat(parsed.symbols()).isEmpty();
        assertThat(parsed.problems())
                .isNotEmpty()
                .allSatisfy(problem -> assertThat(problem)
                        .matches("(line \\d+, column \\d+|unknown position): syntax error")
                        .doesNotContain("sk-not", "apiKey"));
    }

    @Test
    void capsTheNumberOfProblemsReported() {
        // Given: one broken statement per line
        String source = "class Broken {\n" + "    int x = ;\n".repeat(100) + "}\n";

        // When / Then
        assertThat(parser.parse("Broken.java", source).problems())
                .hasSizeLessThanOrEqualTo(JavaParserCodeParser.MAX_PROBLEMS);
    }

    @Test
    void turnsPathologicallyNestedInputIntoAParseFailureInsteadOfAnError() {
        // Given: deep enough to exhaust the parser's recursion
        String source = "class Deep { int x = " + "(".repeat(50_000) + "1" + ")".repeat(50_000) + "; }";

        // When
        ParsedFile parsed = parser.parse("Deep.java", source);

        // Then
        assertThat(parsed.status()).isEqualTo(ParseStatus.PARSE_FAILED);
        assertThat(parsed.problems()).singleElement().asString().startsWith("parser error: ");
    }

    @Test
    void givesOverloadsDistinctKeysButFieldsAndTypesTheirQualifiedName() {
        // Given
        String source = """
                package com.x;
                class Owners {
                    int count;
                    Owners() {}
                    Owners(java.util.List<String> names, int... ids) {}
                    void find(int id) {}
                    void find(String[] names) {}
                }
                """;

        // When
        List<ParsedSymbol> symbols = parser.parse("Owners.java", source).symbols();

        // Then
        assertThat(symbols)
                .extracting(ParsedSymbol::kind, ParsedSymbol::symbolKey)
                .containsExactly(
                        tuple(SymbolKind.CLASS, "com.x.Owners"),
                        tuple(SymbolKind.FIELD, "com.x.Owners#count"),
                        tuple(SymbolKind.CONSTRUCTOR, "com.x.Owners#Owners()"),
                        tuple(SymbolKind.CONSTRUCTOR, "com.x.Owners#Owners(java.util.List,int[])"),
                        tuple(SymbolKind.METHOD, "com.x.Owners#find(int)"),
                        tuple(SymbolKind.METHOD, "com.x.Owners#find(String[])"));
    }
}
