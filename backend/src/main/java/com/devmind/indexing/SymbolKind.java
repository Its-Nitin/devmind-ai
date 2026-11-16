package com.devmind.indexing;

/** What a symbol declares. Matches the {@code code_symbol.kind} CHECK constraint. */
public enum SymbolKind {
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    ANNOTATION,
    METHOD,
    CONSTRUCTOR,
    FIELD;

    /** Returns whether this is a type declaration rather than a member. */
    public boolean isType() {
        return switch (this) {
            case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION -> true;
            case METHOD, CONSTRUCTOR, FIELD -> false;
        };
    }
}
