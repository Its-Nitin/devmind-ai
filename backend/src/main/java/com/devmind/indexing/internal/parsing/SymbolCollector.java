package com.devmind.indexing.internal.parsing;

import com.devmind.indexing.ParsedSymbol;
import com.devmind.indexing.SymbolKind;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Walks one compilation unit and collects its declarations: types at any nesting depth and their methods,
 * constructors and fields. Not collected: local and anonymous classes, enum constants, initializer blocks and
 * annotation members. Single use.
 */
final class SymbolCollector {

    /** Simple names of annotations that mark test code (M2 step 5, rule 8). */
    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "SpringBootTest", "WebMvcTest", "DataJpaTest");

    private final boolean testPath;
    private final List<ParsedSymbol> symbols = new ArrayList<>();

    /** @param path repository-relative; files under {@code src/test/} are test code */
    SymbolCollector(String path) {
        this.testPath = path.startsWith("src/test/") || path.contains("/src/test/");
    }

    List<ParsedSymbol> collect(CompilationUnit unit) {
        String packagePrefix = unit.getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .map(name -> name + ".")
                .orElse("");
        for (TypeDeclaration<?> type : unit.getTypes()) {
            type(type, packagePrefix + type.getNameAsString(), null, false);
        }
        return List.copyOf(symbols);
    }

    private void type(TypeDeclaration<?> type, String qualifiedName, @Nullable Integer parent, boolean enclosingTest) {
        boolean test = enclosingTest || isTestAnnotated(type.getAnnotations());
        int index = add(new ParsedSymbol(
                kindOf(type),
                type.getNameAsString(),
                qualifiedName,
                header(type),
                qualifiedName,
                JavaTypes.modifiers(type.getModifiers()),
                annotations(type.getAnnotations()),
                testPath || test,
                startLine(type, type.getAnnotations()),
                endLine(type),
                parent));
        for (BodyDeclaration<?> member : type.getMembers()) {
            member(member, qualifiedName, index, test);
        }
    }

    private void member(BodyDeclaration<?> member, String typeName, int parent, boolean enclosingTest) {
        switch (member) {
            case TypeDeclaration<?> nested ->
                type(nested, typeName + "." + nested.getNameAsString(), parent, enclosingTest);
            case MethodDeclaration method ->
                callable(
                        SymbolKind.METHOD,
                        method,
                        JavaTypes.signature(
                                JavaTypes.modifiers(method.getModifiers()),
                                method.getTypeParameters(),
                                method.getType(),
                                method.getNameAsString(),
                                method.getParameters()),
                        JavaTypes.erasedParameters(method.getParameters()),
                        typeName,
                        parent,
                        enclosingTest);
            case ConstructorDeclaration constructor ->
                callable(
                        SymbolKind.CONSTRUCTOR,
                        constructor,
                        JavaTypes.signature(
                                JavaTypes.modifiers(constructor.getModifiers()),
                                constructor.getTypeParameters(),
                                null,
                                constructor.getNameAsString(),
                                constructor.getParameters()),
                        JavaTypes.erasedParameters(constructor.getParameters()),
                        typeName,
                        parent,
                        enclosingTest);
            case CompactConstructorDeclaration compact ->
                callable(
                        SymbolKind.CONSTRUCTOR,
                        compact,
                        JavaTypes.signature(
                                JavaTypes.modifiers(compact.getModifiers()),
                                compact.getTypeParameters(),
                                null,
                                compact.getNameAsString(),
                                null),
                        recordComponents(compact),
                        typeName,
                        parent,
                        enclosingTest);
            case FieldDeclaration field -> fields(field, typeName, parent, enclosingTest);
            default -> {
                // initializer blocks, annotation members, enum constants: not symbols in M2
            }
        }
    }

    /**
     * Methods and constructors. Their key adds the erased parameter types, so overloads differ.
     *
     * @param declaration a method, constructor or compact constructor; JavaParser has no narrower common type
     */
    private <D extends Node & NodeWithSimpleName<?> & NodeWithModifiers<?> & NodeWithAnnotations<?>> void callable(
            SymbolKind kind,
            D declaration,
            String signature,
            String erasedParameters,
            String typeName,
            int parent,
            boolean enclosingTest) {
        String name = declaration.getNameAsString();
        String qualifiedName = typeName + "#" + name;
        NodeList<AnnotationExpr> annotations = declaration.getAnnotations();
        add(new ParsedSymbol(
                kind,
                name,
                qualifiedName,
                signature,
                qualifiedName + erasedParameters,
                JavaTypes.modifiers(declaration.getModifiers()),
                annotations(annotations),
                testPath || enclosingTest || isTestAnnotated(annotations),
                startLine(declaration, annotations),
                endLine(declaration),
                parent));
    }

    /** A compact constructor takes the record components as its parameters. */
    private static String recordComponents(CompactConstructorDeclaration compact) {
        return compact.getParentNode()
                .filter(RecordDeclaration.class::isInstance)
                .map(record -> JavaTypes.erasedParameters(((RecordDeclaration) record).getParameters()))
                .orElse("()");
    }

    /** One symbol per variable ({@code int a, b;} gives two), each spanning the whole declaration. */
    private void fields(FieldDeclaration field, String typeName, int parent, boolean enclosingTest) {
        List<String> modifiers = JavaTypes.modifiers(field.getModifiers());
        String modifierPrefix = modifiers.isEmpty() ? "" : String.join(" ", modifiers) + " ";
        for (VariableDeclarator variable : field.getVariables()) {
            String qualifiedName = typeName + "#" + variable.getNameAsString();
            add(new ParsedSymbol(
                    SymbolKind.FIELD,
                    variable.getNameAsString(),
                    qualifiedName,
                    modifierPrefix + JavaTypes.written(variable.getType()) + " " + variable.getNameAsString(),
                    qualifiedName,
                    modifiers,
                    annotations(field.getAnnotations()),
                    testPath || enclosingTest,
                    startLine(field, field.getAnnotations()),
                    endLine(field),
                    parent));
        }
    }

    private int add(ParsedSymbol symbol) {
        symbols.add(symbol);
        return symbols.size() - 1;
    }

    private static SymbolKind kindOf(TypeDeclaration<?> type) {
        return switch (type) {
            case ClassOrInterfaceDeclaration declaration ->
                declaration.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
            case EnumDeclaration ignored -> SymbolKind.ENUM;
            case RecordDeclaration ignored -> SymbolKind.RECORD;
            case AnnotationDeclaration ignored -> SymbolKind.ANNOTATION;
            default -> SymbolKind.CLASS; // JavaParser has no other type declarations
        };
    }

    /**
     * The type's header as written, without annotations or body: {@code public final class Owner<T extends Pet>
     * extends Person implements Named}.
     */
    private static String header(TypeDeclaration<?> type) {
        StringBuilder header = new StringBuilder();
        for (String modifier : JavaTypes.modifiers(type.getModifiers())) {
            header.append(modifier).append(' ');
        }
        header.append(keyword(type)).append(' ').append(type.getNameAsString());
        if (type instanceof NodeWithTypeParameters<?> generic && generic.isGeneric()) {
            header.append(typeParameters(generic.getTypeParameters()));
        }
        switch (type) {
            case ClassOrInterfaceDeclaration declaration -> {
                appendTypes(header, " extends ", declaration.getExtendedTypes());
                appendTypes(header, " implements ", declaration.getImplementedTypes());
            }
            case RecordDeclaration record -> {
                header.append(record.getParameters().stream()
                        .map(component -> JavaTypes.written(component.getType()) + " " + component.getNameAsString())
                        .collect(Collectors.joining(", ", "(", ")")));
                appendTypes(header, " implements ", record.getImplementedTypes());
            }
            case EnumDeclaration declaration -> appendTypes(header, " implements ", declaration.getImplementedTypes());
            default -> {
                // annotation types have no supertypes
            }
        }
        return header.toString();
    }

    private static String keyword(TypeDeclaration<?> type) {
        return switch (kindOf(type)) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record";
            case ANNOTATION -> "@interface";
            default -> "class";
        };
    }

    private static String typeParameters(NodeList<TypeParameter> parameters) {
        return parameters.stream().map(JavaTypes::written).collect(Collectors.joining(", ", "<", ">"));
    }

    private static void appendTypes(StringBuilder header, String keyword, NodeList<ClassOrInterfaceType> types) {
        if (types.isNonEmpty()) {
            header.append(keyword).append(types.stream().map(JavaTypes::written).collect(Collectors.joining(", ")));
        }
    }

    /** Exactly as written, from the source tokens; M5 and M8 read mapping paths out of them. */
    private static List<String> annotations(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .map(annotation ->
                        annotation.getTokenRange().map(TokenRange::toString).orElseGet(annotation::toString))
                .toList();
    }

    private static boolean isTestAnnotated(NodeList<AnnotationExpr> annotations) {
        return annotations.stream()
                .anyMatch(annotation ->
                        TEST_ANNOTATIONS.contains(annotation.getName().getIdentifier()));
    }

    /** The first line of the declaration, its Javadoc or its annotations, whichever comes first (rule 3). */
    private static int startLine(Node declaration, NodeList<AnnotationExpr> annotations) {
        int start = beginLine(declaration);
        if (declaration.getComment().orElse(null) instanceof JavadocComment javadoc) {
            start = Math.min(start, beginLine(javadoc));
        }
        for (AnnotationExpr annotation : annotations) {
            start = Math.min(start, beginLine(annotation));
        }
        return start;
    }

    private static int beginLine(Node node) {
        return node.getRange().orElseThrow(() -> new IllegalStateException("parsed node without a range")).begin.line;
    }

    private static int endLine(Node node) {
        return node.getRange().orElseThrow(() -> new IllegalStateException("parsed node without a range")).end.line;
    }
}
