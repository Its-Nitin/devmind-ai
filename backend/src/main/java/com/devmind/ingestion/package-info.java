/**
 * Ingestion module: reads a project's source exactly as committed at one commit, from a local repository under a
 * server-configured allowed root (SEC-2) or a bounded clone of a public HTTPS repository (SEC-3).
 *
 * <p>Public API: {@link com.devmind.ingestion.SourceSnapshotProvider}, which opens a {@link
 * com.devmind.ingestion.SourceSnapshot} or explains, as a {@link com.devmind.ingestion.SourceProblem}, why it
 * couldn't.
 */
@NullMarked
package com.devmind.ingestion;

import org.jspecify.annotations.NullMarked;
