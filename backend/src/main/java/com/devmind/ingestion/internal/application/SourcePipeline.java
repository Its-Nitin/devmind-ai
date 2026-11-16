package com.devmind.ingestion.internal.application;

import com.devmind.ingestion.ExclusionReason;
import com.devmind.ingestion.FileOutcome;
import com.devmind.ingestion.IngestionPipeline;
import com.devmind.ingestion.NormalizedFile;
import com.devmind.ingestion.SourceFile;
import com.devmind.ingestion.internal.domain.BinarySniffer;
import com.devmind.ingestion.internal.domain.ContentHash;
import com.devmind.ingestion.internal.domain.PathRules;
import com.devmind.ingestion.internal.domain.SecretRedactor;
import com.devmind.ingestion.internal.domain.TextNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** The filter, normalization and redaction steps of M2 step 4, in the table's order. Stateless. */
@Service
class SourcePipeline implements IngestionPipeline {

    /** Bump whenever a rule changes what is kept or what content is produced; it's part of the index config hash. */
    static final String RULES_VERSION = "filters-1";

    @Override
    public String rulesVersion() {
        return RULES_VERSION;
    }

    @Override
    public FileOutcome process(SourceFile file) {
        String path = file.path();
        if (PathRules.inExcludedDirectory(path)) {
            return new FileOutcome.Excluded(ExclusionReason.PATH);
        }
        if (PathRules.isSecretFile(path)) {
            return new FileOutcome.Excluded(ExclusionReason.SECRET_FILE); // before the content is even looked at
        }
        // Size: already enforced by the snapshot, from the blob header.
        byte[] content = file.content();
        if (BinarySniffer.isBinary(content)) {
            return new FileOutcome.Excluded(ExclusionReason.BINARY);
        }
        Optional<String> language = PathRules.language(path);
        if (language.isEmpty()) {
            return new FileOutcome.Excluded(ExclusionReason.EXTENSION);
        }
        return new FileOutcome.Kept(normalize(path, language.orElseThrow(), content));
    }

    private static NormalizedFile normalize(String path, String language, byte[] content) {
        TextNormalizer.Text text = TextNormalizer.normalize(content);
        SecretRedactor.Redacted redacted = SecretRedactor.redact(text.text(), language);
        String result = redacted.text();
        return new NormalizedFile(
                path,
                language,
                result,
                ContentHash.sha256(result),
                result.getBytes(StandardCharsets.UTF_8).length,
                TextNormalizer.lineCount(result),
                redacted.count(),
                text.encodingFallback());
    }
}
