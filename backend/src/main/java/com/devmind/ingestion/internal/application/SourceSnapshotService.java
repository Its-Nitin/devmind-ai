package com.devmind.ingestion.internal.application;

import com.devmind.ingestion.ProjectSource;
import com.devmind.ingestion.SnapshotOutcome;
import com.devmind.ingestion.SourceProblem;
import com.devmind.ingestion.SourceSnapshot;
import com.devmind.ingestion.SourceSnapshotProvider;
import com.devmind.ingestion.internal.domain.RefName;
import com.devmind.ingestion.internal.domain.RemoteUrlPolicy;
import com.devmind.ingestion.internal.domain.SourceRejection;
import com.devmind.ingestion.internal.git.GitSources;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Validates a source and ref, then opens a snapshot through {@link GitSources}. No transaction: opening a source may
 * clone over the network (SPRING-2).
 */
@Service
class SourceSnapshotService implements SourceSnapshotProvider {

    private static final Logger log = LoggerFactory.getLogger(SourceSnapshotService.class);

    private final GitSources git;
    private final RemoteUrlPolicy remoteUrls;

    /** Fails startup if an allowed root or host is invalid, or the clone directory can't be prepared. */
    public SourceSnapshotService(IngestionProperties properties) {
        this.git = new GitSources(properties.allowedRoots(), properties.cloneDirectory(), properties.limits());
        this.remoteUrls = new RemoteUrlPolicy(properties.allowedHosts());
    }

    @Override
    public SnapshotOutcome open(ProjectSource source, String ref) {
        try {
            SourceSnapshot snapshot = open(source, parse(ref));
            log.debug("Opened {} source at {}: {}", source.type(), snapshot.commitSha(), snapshot.stats());
            return new SnapshotOutcome.Opened(snapshot);
        } catch (SourceRejection rejection) {
            return failed(source, rejection);
        }
    }

    private SourceSnapshot open(ProjectSource source, RefName ref) throws SourceRejection {
        return switch (source.type()) {
            case LOCAL -> git.openLocal(source.location(), ref);
            case GIT_PUBLIC -> git.openRemote(validRemote(source.location()), ref);
        };
    }

    private static RefName parse(String ref) throws SourceRejection {
        return RefName.parse(ref).orElseThrow(() -> new SourceRejection(SourceProblem.INVALID_REF));
    }

    private URI validRemote(String location) throws SourceRejection {
        return remoteUrls.validate(location).orElseThrow(() -> new SourceRejection(SourceProblem.URL_NOT_ALLOWED));
    }

    /** Logs once, here: a fault (with its cause) at WARN, a rejected input at INFO (OBS-2). Never the location. */
    private static SnapshotOutcome failed(ProjectSource source, SourceRejection rejection) {
        SourceProblem problem = rejection.problem();
        Throwable cause = rejection.getCause();
        if (cause != null) {
            log.warn("Couldn't open {} source: {}", source.type(), problem.code(), cause);
        } else {
            log.info("Rejected {} source: {}", source.type(), problem.code());
        }
        return new SnapshotOutcome.Failed(problem);
    }
}
