package com.devmind.support;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.ai.EmbeddingPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Deterministic {@link EmbeddingPort} for tests (AI-11): no model, no network.
 *
 * <p>Each word of the text, with identifiers split ({@code findOwnerById} → find, owner, by, id), is hashed to a
 * dimension and a sign, and the counts are L2-normalized. Texts that share words therefore get a higher cosine
 * similarity than texts that don't, so vector-retrieval tests rank meaningfully. Queries and documents are embedded
 * the same way.
 */
public final class FakeEmbedding implements EmbeddingPort {

    /** The default model: the same dimension as the schema's {@code vector(1024)}. */
    public static final EmbeddingModelRef MODEL = new EmbeddingModelRef("fake", "fake-embed", 1024, "fake-digest-1");

    private static final Pattern WORD_BOUNDARY = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern CASE_BOUNDARY = Pattern.compile(
            "(?<=\\p{Ll})(?=\\p{Lu})|(?<=\\p{Lu})(?=\\p{Lu}\\p{Ll})|(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})");

    private final EmbeddingModelRef model;
    private final AtomicInteger documentsEmbedded = new AtomicInteger();
    private final AtomicInteger queriesEmbedded = new AtomicInteger();

    /** A fake for {@link #MODEL}. */
    public FakeEmbedding() {
        this(MODEL);
    }

    /** A fake that reports {@code model}, e.g. another digest to simulate a model change. */
    public FakeEmbedding(EmbeddingModelRef model) {
        this.model = model;
    }

    @Override
    public EmbeddingModelRef model() {
        return model;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        documentsEmbedded.addAndGet(texts.size());
        return texts.stream().map(this::vector).toList();
    }

    @Override
    public float[] embedQuery(String text) {
        queriesEmbedded.incrementAndGet();
        return vector(text);
    }

    /** Returns how many document texts were embedded since creation. */
    public int documentsEmbedded() {
        return documentsEmbedded.get();
    }

    /** Returns how many queries were embedded since creation. */
    public int queriesEmbedded() {
        return queriesEmbedded.get();
    }

    /** Splits text into lowercase words, breaking identifiers at case and letter/digit changes. */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String token : WORD_BOUNDARY.splitAsStream(text).toList()) {
            for (String part : CASE_BOUNDARY.splitAsStream(token).toList()) {
                if (!part.isEmpty()) {
                    words.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        return words;
    }

    private float[] vector(String text) {
        float[] vector = new float[model.dimensions()];
        for (String word : words(text)) {
            byte[] hash = sha256(word);
            int bucket = Math.floorMod(
                    ((hash[0] & 0xff) << 16) | ((hash[1] & 0xff) << 8) | (hash[2] & 0xff), model.dimensions());
            vector[bucket] += (hash[3] & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            // No words: a fixed unit vector, because pgvector's cosine distance is undefined for a zero vector.
            vector[0] = 1f;
            return vector;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    private static byte[] sha256(String word) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(word.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }
}
