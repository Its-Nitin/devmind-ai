package com.devmind.indexing.internal.parsing;

import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * How types, parameter lists and signatures are written into symbols. Source-level only: nothing is resolved yet (M5).
 */
final class JavaTypes {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private JavaTypes() {}

    /**
     * A callable's header as written, e.g. {@code public static <T> List<T> copy(List<T> items, int... limits)}.
     * Built here rather than with JavaParser's {@code getDeclarationAsString}, which adds implicit modifiers
     * ({@code abstract}), drops {@code default} and method type parameters, and pads with a leading space.
     * Parameter annotations are left out to keep the header readable; the symbol's own annotations are kept apart.
     *
     * @param returnType null for constructors
     * @param parameters null for a compact constructor, which is written without a parameter list
     */
    static String signature(
            List<String> modifiers,
            NodeList<TypeParameter> typeParameters,
            @Nullable Type returnType,
            String name,
            @Nullable NodeList<Parameter> parameters) {
        List<String> parts = new ArrayList<>(modifiers);
        if (typeParameters.isNonEmpty()) {
            parts.add(typeParameters.stream().map(JavaTypes::written).collect(Collectors.joining(", ", "<", ">")));
        }
        if (returnType != null) {
            parts.add(written(returnType));
        }
        parts.add(parameters == null ? name : name + parameterList(parameters));
        return String.join(" ", parts);
    }

    /**
     * A node's source text exactly as written, with each run of whitespace collapsed to one space, so {@code Map<String,
     * List<Integer>>} keeps its spacing. JavaParser's own printing normalizes it away.
     */
    static String written(Node node) {
        return node.getTokenRange()
                .map(TokenRange::toString)
                .map(text -> WHITESPACE.matcher(text.strip()).replaceAll(" "))
                .orElseGet(node::toString); // synthesized nodes have no tokens
    }

    private static String parameterList(NodeList<Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> (parameter.isFinal() ? "final " : "")
                        + written(parameter.getType())
                        + (parameter.isVarArgs() ? "..." : "")
                        + " "
                        + parameter.getNameAsString())
                .collect(Collectors.joining(", ", "(", ")"));
    }

    /**
     * The type as written, without type arguments: {@code List<Owner>} is {@code List}, {@code Map.Entry<K, V>[]} is
     * {@code Map.Entry[]}, {@code java.util.List<T>} keeps its qualifier. Type variables stay as named ({@code T}).
     */
    static String erasure(Type type) {
        if (type.isArrayType()) {
            return erasure(type.asArrayType().getComponentType()) + "[]";
        }
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            String name = classType.getNameAsString();
            return classType
                    .getScope()
                    .map(scope -> erasure(scope) + "." + name)
                    .orElse(name);
        }
        return type.asString(); // primitives, var, wildcards: no type arguments to drop
    }

    /** {@code (int,List,String[])}: erased parameter types, a varargs parameter as the array it is. */
    static String erasedParameters(NodeList<Parameter> parameters) {
        return parameters.stream()
                .map(parameter -> erasure(parameter.getType()) + (parameter.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(",", "(", ")"));
    }

    static List<String> modifiers(NodeList<Modifier> modifiers) {
        return modifiers.stream()
                .map(modifier -> modifier.getKeyword().asString())
                .toList();
    }
}
