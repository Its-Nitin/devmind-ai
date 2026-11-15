package com.devmind.job;

/** What a job does. Matches the {@code job.type} CHECK constraint (DB-3); each type has at most one handler. */
public enum JobType {
    /** Index a project at one commit (M2). */
    INDEX,
    /** Change-impact analysis between two revisions (M5). */
    ANALYSIS,
    /** An evaluation run (M3+). */
    EVAL
}
