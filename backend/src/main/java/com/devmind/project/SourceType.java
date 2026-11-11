package com.devmind.project;

/** Where a project's code comes from. Matches the {@code project.source_type} CHECK constraint (DB-3). */
public enum SourceType {
    /** A directory under a server-configured allowed root (SEC-2). */
    LOCAL,
    /** A public HTTPS Git repository on an allow-listed host (SEC-3). */
    GIT_PUBLIC
}
