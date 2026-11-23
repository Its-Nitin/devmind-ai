package com.devmind.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The fake is a test fixture other tests rank with, so its properties are tested too (AI-11). */
class FakeEmbeddingTest {

    private final FakeEmbedding embedding = new FakeEmbedding();

    @Test
    void returnsTheSameUnitVectorForTheSameText() {
        // When
        float[] first = embedding.embedQuery("findOwnerById(int id)");
        float[] second = new FakeEmbedding()
                .embedDocuments(List.of("findOwnerById(int id)"))
                .getFirst();

        // Then
        assertThat(first).hasSize(1024).containsExactly(second);
        assertThat(norm(first)).isCloseTo(1.0, offset(1e-5));
    }

    @Test
    void ranksTextsSharingWordsAboveUnrelatedText() {
        // Given
        float[] query = embedding.embedQuery("find owner by id");

        // When
        double related = cosine(query, embedding.embedQuery("Owner findOwnerById(int id)"));
        double unrelated = cosine(query, embedding.embedQuery("PetTypeFormatter print locale"));

        // Then
        assertThat(related).isGreaterThan(unrelated);
    }

    @Test
    void splitsIdentifiersIntoWords() {
        assertThat(FakeEmbedding.words("OwnerRestControllerV1#getOwner(Integer ownerId)"))
                .containsExactly("owner", "rest", "controller", "v", "1", "get", "owner", "integer", "owner", "id");
    }

    @Test
    void givesTextWithoutWordsANonZeroVector() {
        assertThat(norm(embedding.embedQuery("{ } ;"))).isEqualTo(1.0);
    }

    @Test
    void countsWhatItEmbedded() {
        embedding.embedDocuments(List.of("a", "b"));
        embedding.embedQuery("c");

        assertThat(embedding.documentsEmbedded()).isEqualTo(2);
        assertThat(embedding.queriesEmbedded()).isEqualTo(1);
    }

    private static double norm(float[] vector) {
        return Math.sqrt(cosineDot(vector, vector));
    }

    private static double cosine(float[] a, float[] b) {
        return cosineDot(a, b) / (norm(a) * norm(b));
    }

    private static double cosineDot(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
