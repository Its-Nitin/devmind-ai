package com.devmind.indexing.internal.domain;

import com.devmind.shared.ConflictException;
import com.devmind.shared.NotFoundException;
import com.devmind.shared.UnprocessableException;
import java.io.Serial;

/**
 * Why a revision read can't be answered (M2 step 8). Each is a {@code shared} domain exception, so the one advice maps
 * it to its ProblemDetail (ERR-1). Details never echo the request's path or query (OBS-3).
 */
public final class RevisionReadException {

    private RevisionReadException() {}

    /** No revision of the project matches the SHA or prefix. */
    public static final class RevisionNotFound extends NotFoundException {

        @Serial
        private static final long serialVersionUID = 1L;

        public RevisionNotFound() {
            super("revision-not-found", "No indexed revision matches this commit.");
        }
    }

    /** The commit is being indexed, or its indexing failed; only READY revisions can be read. */
    public static final class RevisionNotReady extends ConflictException {

        @Serial
        private static final long serialVersionUID = 1L;

        public RevisionNotReady() {
            super("revision-not-ready", "This revision isn't ready to read yet.");
        }
    }

    /** The SHA prefix matches more than one indexed commit. */
    public static final class RevisionAmbiguous extends ConflictException {

        @Serial
        private static final long serialVersionUID = 1L;

        public RevisionAmbiguous() {
            super("revision-ambiguous", "This commit prefix matches more than one revision; use more characters.");
        }
    }

    /** The revision has no file at the path. */
    public static final class FileNotFound extends NotFoundException {

        @Serial
        private static final long serialVersionUID = 1L;

        public FileNotFound() {
            super("file-not-found", "The revision has no file at this path.");
        }
    }

    /** The revision has no directory at the path. */
    public static final class DirectoryNotFound extends NotFoundException {

        @Serial
        private static final long serialVersionUID = 1L;

        public DirectoryNotFound() {
            super("directory-not-found", "The revision has no directory at this path.");
        }
    }

    /** No symbol with the ID is in the revision. */
    public static final class SymbolNotFound extends NotFoundException {

        @Serial
        private static final long serialVersionUID = 1L;

        public SymbolNotFound() {
            super("symbol-not-found", "The revision has no such symbol.");
        }
    }

    /** The requested lines don't fit the file or the per-call limit. */
    public static final class LineRangeInvalid extends UnprocessableException {

        @Serial
        private static final long serialVersionUID = 1L;

        public LineRangeInvalid(String detail) {
            super("line-range-invalid", detail);
        }
    }
}
