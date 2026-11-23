package com.devmind.ai.internal.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.devmind.ai.EmbeddingModelRef;
import com.devmind.ai.EmbeddingUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter against a stand-in for Ollama's HTTP API on loopback: request shape, batching, prefixes, model
 * resolution, the dimension guard, and how each failure maps to an {@link EmbeddingUnavailableException} code. A real
 * JDK client with real timeouts, but no model and no outside network (AI-11, TEST-5).
 */
class OllamaEmbeddingAdapterTest {

    private static final String DIGEST = "468836162de7f81e041c43663fedbbba921dcea9b9fefea135685a39b2d83dd8";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final StubOllama ollama = new StubOllama();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void startStub() {
        ollama.start();
    }

    @AfterEach
    void stopStub() {
        ollama.stop();
    }

    @Nested
    class Resolution {

        @Test
        void resolvesTheInstalledDigestAndChecksTheDimensionOnce() {
            // Given: the configured name has no tag, Ollama lists it as :latest
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When
            EmbeddingModelRef first = adapter.model();
            EmbeddingModelRef second = adapter.model();

            // Then: one tags lookup and one probe, then cached
            assertThat(first).isEqualTo(new EmbeddingModelRef("ollama", "mxbai-embed-large", 4, DIGEST));
            assertThat(second).isSameAs(first);
            assertThat(ollama.tagRequests).hasSize(1);
            assertThat(ollama.embedInputs).containsExactly(List.of("doc: dimension check"));
        }

        @Test
        void refusesAModelOfAnotherDimension() {
            // Given: the server's model returns 3 floats, the schema stores 4
            ollama.dimensions = 3;
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When / Then: a configuration error that stops startup, not a transient failure
            assertThatIllegalStateException()
                    .isThrownBy(adapter::checkAtStartup)
                    .withMessageContaining("returns 3-dimensional vectors")
                    .withMessageContaining("dimensions is 4");
        }

        @Test
        void startsWithoutAReachableServerAndFailsTheCallInstead() throws IOException {
            // Given: nothing listens on the port
            OllamaEmbeddingAdapter adapter = adapter(properties(URI.create("http://127.0.0.1:" + freePort())));

            // When / Then
            assertThatCode(adapter::checkAtStartup).doesNotThrowAnyException();
            assertThatExceptionOfType(EmbeddingUnavailableException.class)
                    .isThrownBy(() -> adapter.embedQuery("owners"))
                    .extracting(EmbeddingUnavailableException::code)
                    .isEqualTo(EmbeddingUnavailableException.UNREACHABLE);
        }

        @Test
        void retriesResolutionAfterAFailure() {
            // Given: the first tags lookup fails with 503
            ollama.tagsStatus = 503;
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));
            assertThatExceptionOfType(EmbeddingUnavailableException.class).isThrownBy(adapter::model);

            // When: the server recovers
            ollama.tagsStatus = 200;

            // Then: the failure wasn't cached
            assertThat(adapter.model().digest()).isEqualTo(DIGEST);
        }

        @Test
        void reportsAModelTheServerDoesNotHave() {
            // Given
            ollama.installedName = "nomic-embed-text:latest";
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When / Then
            assertThatExceptionOfType(EmbeddingUnavailableException.class)
                    .isThrownBy(adapter::model)
                    .extracting(EmbeddingUnavailableException::code)
                    .isEqualTo(EmbeddingUnavailableException.MODEL_NOT_FOUND);
        }
    }

    @Nested
    class Embedding {

        @Test
        void sendsDocumentsInBatchesWithTheDocumentPrefixAndKeepsTheirOrder() {
            // Given: batches of 2
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When
            List<float[]> vectors = adapter.embedDocuments(List.of("a", "bb", "ccc", "dddd", "eeeee"));

            // Then: the probe, then 2 + 2 + 1, each for the configured model with truncation on
            assertThat(ollama.embedInputs)
                    .containsExactly(
                            List.of("doc: dimension check"),
                            List.of("doc: a", "doc: bb"),
                            List.of("doc: ccc", "doc: dddd"),
                            List.of("doc: eeeee"));
            assertThat(ollama.embedModels).containsOnly("mxbai-embed-large");
            assertThat(ollama.embedTruncate).containsOnly(true);
            // the stub's first component is the input's length, so it shows the order
            assertThat(vectors).extracting(vector -> vector[0]).containsExactly(6f, 7f, 8f, 9f, 10f);
        }

        @Test
        void embedsQueriesWithTheQueryPrefix() {
            // Given
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When
            float[] vector = adapter.embedQuery("owners");

            // Then
            assertThat(ollama.embedInputs.getLast()).containsExactly("query: owners");
            assertThat(vector).hasSize(4);
        }

        @Test
        void makesNoCallForNoDocuments() {
            // Given
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When
            List<float[]> vectors = adapter.embedDocuments(List.of());

            // Then: not even the model resolution
            assertThat(vectors).isEmpty();
            assertThat(ollama.tagRequests).isEmpty();
            assertThat(ollama.embedInputs).isEmpty();
        }

        @Test
        void timesOutASlowResponse() {
            // Given: a read timeout of 200 ms and a server that answers embeds only when released
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url(), Duration.ofMillis(200)));
            ollama.holdEmbeds = new CountDownLatch(1);

            // When / Then
            try {
                assertThatExceptionOfType(EmbeddingUnavailableException.class)
                        .isThrownBy(() -> adapter.embedQuery("owners"))
                        .extracting(EmbeddingUnavailableException::code)
                        .isEqualTo(EmbeddingUnavailableException.UNREACHABLE);
            } finally {
                ollama.holdEmbeds.countDown();
            }
        }

        @Test
        void rejectsAResponseWithTooFewVectors() {
            // Given: resolved, then the server starts dropping a vector
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));
            adapter.model();
            ollama.dropOneVector = true;

            // When / Then
            assertThatExceptionOfType(EmbeddingUnavailableException.class)
                    .isThrownBy(() -> adapter.embedDocuments(List.of("a", "b")))
                    .extracting(EmbeddingUnavailableException::code)
                    .isEqualTo(EmbeddingUnavailableException.INVALID_RESPONSE);
        }

        @Test
        void rejectsVectorsThatChangedLengthSinceResolution() {
            // Given: the model was swapped under its name after resolution
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));
            adapter.model();
            ollama.dimensions = 8;

            // When / Then
            assertThatExceptionOfType(EmbeddingUnavailableException.class)
                    .isThrownBy(() -> adapter.embedQuery("owners"))
                    .extracting(EmbeddingUnavailableException::code)
                    .isEqualTo(EmbeddingUnavailableException.INVALID_RESPONSE);
        }

        @Test
        void mapsAMissingModelOnEmbedToModelNotFound() {
            // Given: resolved, then the model was removed (Ollama answers 404)
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));
            adapter.model();
            ollama.embedStatus = 404;

            // When / Then
            assertThatExceptionOfType(EmbeddingUnavailableException.class)
                    .isThrownBy(() -> adapter.embedQuery("owners"))
                    .extracting(EmbeddingUnavailableException::code)
                    .isEqualTo(EmbeddingUnavailableException.MODEL_NOT_FOUND);
        }

        @Test
        void countsTextsAndTimesRequestsByKind() {
            // Given
            OllamaEmbeddingAdapter adapter = adapter(properties(ollama.url()));

            // When
            adapter.embedDocuments(List.of("a", "b", "c"));
            adapter.embedQuery("q");

            // Then: low-cardinality tags only (OBS-4)
            assertThat(meters.get("devmind.embedding.texts")
                            .tag("kind", "document")
                            .counter()
                            .count())
                    .isEqualTo(3);
            assertThat(meters.get("devmind.embedding.texts")
                            .tag("kind", "query")
                            .counter()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("devmind.embedding.latency")
                            .tag("kind", "document")
                            .timer()
                            .count())
                    .isEqualTo(2);
        }
    }

    private OllamaEmbeddingAdapter adapter(EmbeddingProperties properties) {
        return new OllamaEmbeddingAdapter(OllamaEmbeddingAdapter.restClient(properties), properties, meters);
    }

    private static EmbeddingProperties properties(URI baseUrl) {
        return properties(baseUrl, Duration.ofSeconds(5));
    }

    private static EmbeddingProperties properties(URI baseUrl, Duration readTimeout) {
        return new EmbeddingProperties(
                EmbeddingProperties.Provider.OLLAMA,
                baseUrl,
                "mxbai-embed-large",
                4,
                "doc: ",
                "query: ",
                2,
                Duration.ofSeconds(2),
                readTimeout);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Answers {@code /api/tags} and {@code /api/embed} like Ollama, and records what it was sent. */
    private static final class StubOllama {

        final List<String> tagRequests = new CopyOnWriteArrayList<>();
        final List<List<String>> embedInputs = new CopyOnWriteArrayList<>();
        final List<String> embedModels = new CopyOnWriteArrayList<>();
        final List<Boolean> embedTruncate = new CopyOnWriteArrayList<>();
        volatile String installedName = "mxbai-embed-large:latest";
        volatile int dimensions = 4;
        volatile int tagsStatus = 200;
        volatile int embedStatus = 200;
        volatile boolean dropOneVector;
        volatile CountDownLatch holdEmbeds = new CountDownLatch(0);

        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpServer server;

        StubOllama() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void start() {
            server.setExecutor(executor);
            server.createContext("/api/tags", this::tags);
            server.createContext("/api/embed", this::embed);
            server.start();
        }

        void stop() {
            holdEmbeds.countDown();
            server.stop(0);
            executor.shutdownNow();
        }

        URI url() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        }

        private void tags(HttpExchange exchange) throws IOException {
            tagRequests.add(exchange.getRequestMethod());
            if (tagsStatus != 200) {
                respond(exchange, tagsStatus, "{\"error\":\"busy\"}");
                return;
            }
            respond(exchange, 200, """
                    {"models":[{"name":"qwen2.5-coder:7b","digest":"other"},
                               {"name":"%s","model":"%s","digest":"%s","details":{"family":"bert"}}]}""".formatted(installedName, installedName, DIGEST));
        }

        private void embed(HttpExchange exchange) throws IOException {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            List<String> inputs =
                    request.get("input").valueStream().map(JsonNode::asString).toList();
            embedInputs.add(inputs);
            embedModels.add(request.get("model").asString());
            embedTruncate.add(request.get("truncate").asBoolean());
            try {
                if (!holdEmbeds.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test never released the held embed request");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (embedStatus != 200) {
                respond(exchange, embedStatus, "{\"error\":\"model not found\"}");
                return;
            }
            StringBuilder vectors = new StringBuilder();
            int count = dropOneVector ? inputs.size() - 1 : inputs.size();
            for (int i = 0; i < count; i++) {
                vectors.append(i == 0 ? "" : ",").append(vector(inputs.get(i).length()));
            }
            respond(exchange, 200, "{\"model\":\"mxbai-embed-large\",\"embeddings\":[" + vectors + "]}");
        }

        private String vector(int first) {
            StringBuilder vector = new StringBuilder("[").append(first);
            for (int i = 1; i < dimensions; i++) {
                vector.append(",0.5");
            }
            return vector.append(']').toString();
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
