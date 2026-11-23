package com.devmind.ai.internal.embedding;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.ai.EmbeddingPort;
import com.devmind.ai.EmbeddingUnavailableException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link EmbeddingPort} on Ollama's HTTP API: {@code POST /api/embed} for vectors and {@code GET /api/tags} for the
 * installed model's digest (ADR-0006).
 *
 * <p>The model is resolved once, on first use or by the startup check: its digest is read and one probe text is
 * embedded to check the vector length against {@code devmind.ai.embedding.dimensions}. A wrong length is a
 * configuration error and fails hard, because vectors of another length can't be stored or compared. An unreachable
 * server is only transient, so the app still starts and resolution is retried on the next call.
 *
 * <p>No retries here: indexing jobs retry with backoff (ERR-3), and a failed query embedding degrades retrieval
 * instead of failing it. Texts and response bodies are never logged (OBS-3).
 */
final class OllamaEmbeddingAdapter implements EmbeddingPort {

    static final String PROVIDER = "ollama";

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingAdapter.class);

    private static final String PROBE_TEXT = "dimension check";

    private final RestClient http;
    private final EmbeddingProperties properties;
    private final Timer documentLatency;
    private final Timer queryLatency;
    private final Counter documentTexts;
    private final Counter queryTexts;
    private final ReentrantLock resolution = new ReentrantLock();
    private volatile @Nullable EmbeddingModelRef resolved;

    OllamaEmbeddingAdapter(RestClient http, EmbeddingProperties properties, MeterRegistry meters) {
        this.http = http;
        this.properties = properties;
        this.documentLatency = latencyTimer(meters, "document");
        this.queryLatency = latencyTimer(meters, "query");
        this.documentTexts = textCounter(meters, "document");
        this.queryTexts = textCounter(meters, "query");
    }

    /** A client with explicit connect and read timeouts (SPRING-6, ERR-6) and no redirects. */
    static RestClient restClient(EmbeddingProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Ollama speaks HTTP/1.1; skip the h2c upgrade attempt
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(properties.readTimeout());
        String baseUrl = properties.baseUrl().toString().replaceFirst("/+$", "");
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public EmbeddingModelRef model() {
        EmbeddingModelRef current = resolved;
        if (current != null) {
            return current;
        }
        resolution.lock();
        try {
            current = resolved;
            if (current == null) {
                current = resolve();
                resolved = current;
            }
            return current;
        } finally {
            resolution.unlock();
        }
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        int dimensions = model().dimensions();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            List<String> batch = texts.subList(from, Math.min(from + properties.batchSize(), texts.size())).stream()
                    .map(text -> properties.documentPrefix() + text)
                    .toList();
            vectors.addAll(documentLatency.record(() -> embedChecked(batch, dimensions)));
        }
        documentTexts.increment(texts.size());
        return List.copyOf(vectors);
    }

    @Override
    public float[] embedQuery(String text) {
        int dimensions = model().dimensions();
        List<float[]> vectors =
                queryLatency.record(() -> embedChecked(List.of(properties.queryPrefix() + text), dimensions));
        queryTexts.increment();
        return vectors.getFirst();
    }

    /** Resolves the model now, logging instead of failing when the server is merely unreachable. */
    void checkAtStartup() {
        try {
            model();
        } catch (EmbeddingUnavailableException e) {
            log.warn(
                    "Embedding model {} isn't available yet ({}); indexing and vector search wait for it",
                    properties.model(),
                    e.code());
        }
    }

    private EmbeddingModelRef resolve() {
        String digest = installedDigest();
        float[] probe = embed(List.of(properties.documentPrefix() + PROBE_TEXT)).getFirst();
        if (probe.length != properties.dimensions()) {
            throw new IllegalStateException("Embedding model " + properties.model() + " returns " + probe.length
                    + "-dimensional vectors, but devmind.ai.embedding.dimensions is " + properties.dimensions()
                    + ". Changing the model's dimension needs a new migration (ADR-0006).");
        }
        EmbeddingModelRef model = new EmbeddingModelRef(PROVIDER, properties.model(), properties.dimensions(), digest);
        log.info(
                "Embedding model {} ready: {} dimensions, digest {}", model.name(), model.dimensions(), model.digest());
        return model;
    }

    private String installedDigest() {
        TagsResponse tags = call(() -> http.get().uri("/api/tags").retrieve().body(TagsResponse.class));
        if (tags == null || tags.models() == null) {
            throw invalidResponse();
        }
        String wanted = properties.model();
        String wantedLatest = wanted.contains(":") ? wanted : wanted + ":latest";
        for (InstalledModel installed : tags.models()) {
            if (wanted.equals(installed.name()) || wantedLatest.equals(installed.name())) {
                String digest = installed.digest();
                if (digest == null || digest.isBlank()) {
                    throw invalidResponse();
                }
                return digest;
            }
        }
        throw modelNotFound();
    }

    private List<float[]> embedChecked(List<String> inputs, int dimensions) {
        List<float[]> vectors = embed(inputs);
        for (float[] vector : vectors) {
            if (vector.length != dimensions) {
                // The model changed under its name since resolution; nothing from it is comparable.
                log.warn("Embedding model {} returned a {}-dimensional vector", properties.model(), vector.length);
                throw invalidResponse();
            }
        }
        return vectors;
    }

    private List<float[]> embed(List<String> inputs) {
        EmbedResponse response = call(() -> http.post()
                .uri("/api/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new EmbedRequest(properties.model(), inputs, true))
                .retrieve()
                .body(EmbedResponse.class));
        if (response == null
                || response.embeddings() == null
                || response.embeddings().size() != inputs.size()) {
            throw invalidResponse();
        }
        return response.embeddings();
    }

    private <T> @Nullable T call(Call<T> call) {
        try {
            return call.run();
        } catch (HttpStatusCodeException e) {
            // The status only: the body can echo the request (OBS-3).
            log.warn(
                    "Embedding request to the model server failed with HTTP {}",
                    e.getStatusCode().value());
            throw e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) ? modelNotFound() : unreachable();
        } catch (ResourceAccessException e) {
            log.warn("Embedding model server unreachable: {}", rootCauseName(e));
            throw unreachable();
        } catch (RestClientException e) {
            log.warn("Embedding model server sent an unreadable response: {}", rootCauseName(e));
            throw invalidResponse();
        }
    }

    private EmbeddingUnavailableException unreachable() {
        return new EmbeddingUnavailableException(
                EmbeddingUnavailableException.UNREACHABLE, "The embedding model server can't be reached right now.");
    }

    private EmbeddingUnavailableException modelNotFound() {
        return new EmbeddingUnavailableException(
                EmbeddingUnavailableException.MODEL_NOT_FOUND,
                "The embedding model server doesn't have the configured model.");
    }

    private EmbeddingUnavailableException invalidResponse() {
        return new EmbeddingUnavailableException(
                EmbeddingUnavailableException.INVALID_RESPONSE,
                "The embedding model server sent a response that can't be used.");
    }

    private static String rootCauseName(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure).getClass().getSimpleName();
    }

    private static Timer latencyTimer(MeterRegistry meters, String kind) {
        return Timer.builder("devmind.embedding.latency")
                .description("Time per embedding request to the model server")
                .tag("kind", kind)
                .register(meters);
    }

    private static Counter textCounter(MeterRegistry meters, String kind) {
        return Counter.builder("devmind.embedding.texts")
                .description("Texts embedded")
                .tag("kind", kind)
                .register(meters);
    }

    @FunctionalInterface
    private interface Call<T> {
        @Nullable
        T run();
    }

    private record EmbedRequest(String model, List<String> input, boolean truncate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbedResponse(@Nullable List<float[]> embeddings) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TagsResponse(@Nullable List<InstalledModel> models) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InstalledModel(
            @Nullable String name, @Nullable String digest) {}
}
