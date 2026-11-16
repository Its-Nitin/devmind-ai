package com.devmind.indexing.internal.parsing;

import com.devmind.indexing.CodeParser;
import com.devmind.indexing.ParsedFile;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.Problem;
import com.github.javaparser.Range;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Java declarations via JavaParser (ADR-0004; M2 step 5). */
@Component
class JavaParserCodeParser implements CodeParser {

    static final String VERSION = "java-1";

    /** Enough to locate a failure; a file with more problems is broken throughout. */
    static final int MAX_PROBLEMS = 20;

    /** The highest level this JavaParser knows, so newer target code still parses (M2 pitfalls). */
    private static final LanguageLevel LANGUAGE_LEVEL = LanguageLevel.JAVA_26;

    private static final Logger log = LoggerFactory.getLogger(JavaParserCodeParser.class);

    @Override
    public boolean supports(String language) {
        return "java".equals(language);
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ParsedFile parse(String path, String content) {
        try {
            // A parser per call: JavaParser instances aren't thread-safe, and building one is cheap.
            ParseResult<CompilationUnit> result = new JavaParser(configuration()).parse(content);
            Optional<CompilationUnit> unit = result.getResult();
            if (!result.isSuccessful() || unit.isEmpty()) {
                return ParsedFile.failed(problems(result.getProblems()));
            }
            return ParsedFile.parsed(new SymbolCollector(path).collect(unit.orElseThrow()));
        } catch (RuntimeException | StackOverflowError ex) {
            // Parser bugs and pathologically nested input are per-file failures, never a failed index run (ERR-4).
            // Only the type is logged: messages can quote the source (OBS-3).
            log.warn("Parsing {} failed with {}", path, ex.getClass().getName());
            return ParsedFile.failed(List.of("parser error: " + ex.getClass().getSimpleName()));
        }
    }

    private static ParserConfiguration configuration() {
        return new ParserConfiguration().setLanguageLevel(LANGUAGE_LEVEL).setAttributeComments(true);
    }

    /** Positions only: JavaParser's messages quote the offending tokens, which may be anything in the file. */
    private static List<String> problems(List<Problem> problems) {
        List<String> positions = problems.stream()
                .limit(MAX_PROBLEMS)
                .map(problem -> problem.getLocation()
                                .flatMap(TokenRange::toRange)
                                .map(JavaParserCodeParser::position)
                                .orElse("unknown position")
                        + ": syntax error")
                .toList();
        return positions.isEmpty() ? List.of("unknown position: syntax error") : positions;
    }

    private static String position(Range range) {
        return "line " + range.begin.line + ", column " + range.begin.column;
    }
}
