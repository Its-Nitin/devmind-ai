package com.devmind.indexing.internal.web;

import static com.devmind.support.GitFixture.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.indexing.internal.application.IndexFixtures;
import com.devmind.project.ProjectAccessPolicy;
import com.devmind.project.ProjectId;
import com.devmind.support.GitFixture;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import com.devmind.support.TestUsers.SignedInUser;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The revision read API (M2 step 8) over a fixture repository indexed for real. */
@IntegrationTest
@AutoConfigureMockMvc
class RevisionControllerTest {

    @TempDir
    Path temp; // under java.io.tmpdir, the test profile's allowed root

    private final MockMvcTester mvc;
    private final JsonMapper json;
    private final JdbcClient jdbc;
    private final ApplicationContext context;
    private final ProjectAccessPolicy accessPolicy;
    private final SignedInUser owner;

    private GitFixture repo;
    private RevCommit commit;
    private String project;
    private long projectId;

    @Autowired
    RevisionControllerTest(
            MockMvcTester mvc,
            JsonMapper json,
            JdbcClient jdbc,
            ApplicationContext context,
            ProjectAccessPolicy accessPolicy,
            UserRegistrationService registration) {
        this.mvc = mvc;
        this.json = json;
        this.jdbc = jdbc;
        this.context = context;
        this.accessPolicy = accessPolicy;
        this.owner = new TestUsers(mvc, registration).signIn();
    }

    @BeforeEach
    void indexAFixtureRepository() {
        repo = GitFixture.init(temp.resolve("app"));
        commit = repo.commit(
                "main",
                tree().file("README.md", "# Owners\n\nA demo.\n")
                        .file("src/main/java/com/acme/Owner.java", """
                                package com.acme;

                                public class Owner {
                                    private final int id;

                                    public Owner(int id) {
                                        this.id = id;
                                    }

                                    public int id() {
                                        return id;
                                    }
                                }
                                """)
                        .file("src/main/java/com/acme/OwnerService.java", """
                                package com.acme;

                                class OwnerService {
                                    Owner findById(int id) {
                                        return new Owner(id);
                                    }
                                }
                                """)
                        .file(
                                "src/main/resources/application.yml",
                                "spring:\n  datasource:\n    password: s3cr3tvalue\n")
                        .file("docs/big.md", lines(2500))
                        .file("docs/empty.md", ""));
        project = createProject(temp.resolve("app").toString());
        projectId = accessPolicy.requireOwner(owner.id(), new ProjectId(UUID.fromString(project)));
        IndexFixtures.index(context, projectId, temp.resolve("app"), commit.name());
    }

    @AfterEach
    void closeRepository() {
        repo.close();
    }

    @Nested
    class Revisions {

        @Test
        void listsRevisionsNewestFirstWithStatsOnceReady() {
            // Given: a second commit still being indexed
            String indexing = "1".repeat(40);
            insertRevision(indexing, "INDEXING");

            // When
            JsonNode page = body(get(base() + "?size=10"));

            // Then
            assertThat(page.at("/content/0/commitSha").asString()).isEqualTo(indexing);
            assertThat(page.at("/content/0/status").asString()).isEqualTo("INDEXING");
            assertThat(page.at("/content/0/stats").isNull()).isTrue();
            assertThat(page.at("/content/1/commitSha").asString()).isEqualTo(commit.name());
            assertThat(page.at("/content/1/status").asString()).isEqualTo("READY");
            assertThat(page.at("/content/1/stats/filesKept").asInt()).isEqualTo(6);
            assertThat(page.at("/content/1/stats/redactions").asInt()).isEqualTo(1);
            assertThat(page.at("/page/totalElements").asInt()).isEqualTo(2);
        }

        @Test
        void acceptsAUniquePrefixAndEchoesTheFullSha() {
            assertThat(body(get(base() + "/" + commit.name().substring(0, 7) + "/files"))
                            .get("commitSha")
                            .asString())
                    .isEqualTo(commit.name());
        }

        @Test
        void refusesRevisionsThatAreNotReadyAmbiguousUnknownOrMalformed() {
            // Given
            insertRevision("2".repeat(40), "INDEXING");
            insertRevision("abcdef0" + "1".repeat(33), "READY");
            insertRevision("abcdef0" + "2".repeat(33), "READY");

            // When / Then
            assertCode(get(base() + "/" + "2".repeat(40) + "/files"), HttpStatus.CONFLICT, "revision-not-ready");
            assertCode(get(base() + "/abcdef0/files"), HttpStatus.CONFLICT, "revision-ambiguous");
            assertCode(get(base() + "/1234567/files"), HttpStatus.NOT_FOUND, "revision-not-found");
            assertThat(get(base() + "/ABCDEF0/files")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(base() + "/abc/files")).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class Files {

        @Test
        void listsTheRootDirectoriesFirstThenFiles() {
            // When
            JsonNode listing = body(get(revision() + "/files"));

            // Then
            assertThat(listing.get("path").asString()).isEmpty();
            assertThat(listing.get("truncated").asBoolean()).isFalse();
            assertThat(entries(listing)).isEqualTo("docs:DIRECTORY:docs, src:DIRECTORY:src, README.md:FILE:README.md");
        }

        @Test
        void listsASubdirectoryWithOrWithoutTrailingSlash() {
            String expected = "Owner.java:FILE:src/main/java/com/acme/Owner.java, "
                    + "OwnerService.java:FILE:src/main/java/com/acme/OwnerService.java";
            assertThat(entries(body(get(revision() + "/files?path=src/main/java/com/acme"))))
                    .isEqualTo(expected);
            assertThat(entries(body(get(revision() + "/files?path=/src/main/java/com/acme/"))))
                    .isEqualTo(expected);
        }

        @Test
        void answersMissingDirectoriesAndFilesWith404() {
            assertCode(get(revision() + "/files?path=nope"), HttpStatus.NOT_FOUND, "directory-not-found");
            assertCode(get(revision() + "/files?path=README.md"), HttpStatus.NOT_FOUND, "directory-not-found");
            assertCode(get(revision() + "/files?path=src/../docs"), HttpStatus.NOT_FOUND, "directory-not-found");
            assertCode(get(revision() + "/file?path=nope.md"), HttpStatus.NOT_FOUND, "file-not-found");
            assertCode(get(revision() + "/file?path=../README.md"), HttpStatus.NOT_FOUND, "file-not-found");
        }

        @Test
        void readsAWholeFileOrARangeOfIt() {
            assertThat(get(revision() + "/file?path=README.md"))
                    .hasStatusOk()
                    .bodyJson()
                    .isLenientlyEqualTo("""
                    {"commitSha": "%s", "path": "README.md", "language": "markdown", "lineCount": 3,
                     "from": 1, "to": 3, "content": "# Owners\\n\\nA demo."}
                    """.formatted(commit.name()));
            assertThat(get(revision() + "/file?path=README.md&from=3&to=99"))
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"from": 3, "to": 3, "content": "A demo."}
                            """);
        }

        @Test
        void servesRedactedContent() {
            assertThat(body(get(revision() + "/file?path=src/main/resources/application.yml"))
                            .get("content")
                            .asString())
                    .doesNotContain("s3cr3tvalue")
                    .contains("«REDACTED»");
        }

        @Test
        void capsAReadAt2000LinesAndRejectsBadRanges() {
            assertThat(get(revision() + "/file?path=docs/big.md")).bodyJson().isLenientlyEqualTo("""
                            {"lineCount": 2500, "from": 1, "to": 2000}
                            """);
            assertThat(get(revision() + "/file?path=docs/big.md&from=2001"))
                    .bodyJson()
                    .isLenientlyEqualTo("""
                            {"from": 2001, "to": 2500}
                            """);
            assertCode(
                    get(revision() + "/file?path=docs/big.md&from=1&to=2001"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "line-range-invalid");
            assertCode(
                    get(revision() + "/file?path=docs/big.md&from=2501"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "line-range-invalid");
            assertCode(
                    get(revision() + "/file?path=docs/big.md&from=10&to=5"),
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "line-range-invalid");
            assertThat(get(revision() + "/file?path=docs/big.md&from=0")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        void readsAnEmptyFileAsNoLines() {
            assertThat(get(revision() + "/file?path=docs/empty.md")).bodyJson().isLenientlyEqualTo("""
                            {"lineCount": 0, "from": 0, "to": 0, "content": ""}
                            """);
        }
    }

    @Nested
    class Symbols {

        @Test
        void findsExactNameMatchesFirstWithTheirLocation() {
            // When
            JsonNode results = body(get(revision() + "/symbols?q=Owner"));

            // Then: the class and its constructor are both named Owner; the class sorts first by qualified name
            assertThat(names(results)).startsWith("CLASS Owner", "CONSTRUCTOR Owner");
            assertThat(results.get("commitSha").asString()).isEqualTo(commit.name());
            assertThat(results.at("/content/0/path").asString()).isEqualTo("src/main/java/com/acme/Owner.java");
            assertThat(results.at("/content/0/startLine").asInt()).isEqualTo(3);
            assertThat(results.at("/content/0/endLine").asInt()).isEqualTo(13);
        }

        @Test
        void findsSimilarNamesDespiteATypo() {
            assertThat(names(body(get(revision() + "/symbols?q=OwnerServise")))).contains("CLASS OwnerService");
        }

        @Test
        void filtersByKindMatchesQualifiedNamesAndPages() {
            assertThat(names(body(get(revision() + "/symbols?q=Owner&kind=CONSTRUCTOR"))))
                    .containsExactly("CONSTRUCTOR Owner");
            assertThat(names(body(get(revision() + "/symbols?q=com.acme.OwnerService"))))
                    .first()
                    .isEqualTo("CLASS OwnerService");
            JsonNode firstPage = body(get(revision() + "/symbols?q=Owner&size=1"));
            assertThat(firstPage.get("content")).hasSize(1);
            assertThat(firstPage.at("/page/totalElements").asInt()).isGreaterThan(1);
            assertThat(body(get(revision() + "/symbols?q=zzzzzz")).get("content"))
                    .isEmpty();
            assertThat(get(revision() + "/symbols?q=")).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(get(revision() + "/symbols?q=Owner&kind=TABLE")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        @Test
        void returnsASymbolWithItsMembersAndParent() {
            // Given
            String classId = symbolId("CLASS", "Owner");

            // When
            JsonNode detail = body(get(revision() + "/symbols/" + classId));

            // Then
            assertThat(detail.at("/symbol/qualifiedName").asString()).isEqualTo("com.acme.Owner");
            assertThat(detail.get("modifiers").get(0).asString()).isEqualTo("public");
            assertThat(detail.get("parentId").isNull()).isTrue();
            assertThat(names(detail.get("children"))).containsExactly("FIELD id", "CONSTRUCTOR Owner", "METHOD id");
            String memberId = detail.at("/children/2/symbolId").asString();
            assertThat(body(get(revision() + "/symbols/" + memberId))
                            .get("parentId")
                            .asString())
                    .isEqualTo(classId);
        }

        @Test
        void answersSymbolsOutsideTheRevisionWith404() {
            assertCode(get(revision() + "/symbols/" + UUID.randomUUID()), HttpStatus.NOT_FOUND, "symbol-not-found");
            assertThat(get(revision() + "/symbols/not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);
        }
    }

    private String base() {
        return "/api/projects/" + project + "/revisions";
    }

    private String revision() {
        return base() + "/" + commit.name();
    }

    private MvcTestResult get(String uri) {
        return mvc.get().uri(uri).session(owner.session()).exchange();
    }

    private JsonNode body(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        try {
            return json.readTree(result.getResponse().getContentAsString());
        } catch (UnsupportedEncodingException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void assertCode(MvcTestResult result, HttpStatus status, String code) {
        assertThat(result).hasStatus(status).bodyJson().isLenientlyEqualTo("""
                {"code": "%s"}
                """.formatted(code));
    }

    private static String entries(JsonNode listing) {
        return listing.get("entries")
                .valueStream()
                .map(entry -> entry.get("name").asString() + ":"
                        + entry.get("type").asString() + ":" + entry.get("path").asString())
                .collect(Collectors.joining(", "));
    }

    private static java.util.List<String> names(JsonNode results) {
        JsonNode symbols = results.has("content") ? results.get("content") : results;
        return symbols.valueStream()
                .map(symbol ->
                        symbol.get("kind").asString() + " " + symbol.get("name").asString())
                .toList();
    }

    private String symbolId(String kind, String name) {
        return body(get(revision() + "/symbols?q=" + name + "&kind=" + kind))
                .at("/content/0/symbolId")
                .asString();
    }

    private void insertRevision(String sha, String status) {
        jdbc.sql("""
                        INSERT INTO repository_revision (project_id, commit_sha, index_config_hash, status, created_at,
                                                         ready_at)
                        VALUES (?, ?, ?, ?, now(), CASE WHEN ? = 'READY' THEN now() END)
                        """).params(projectId, sha, "0".repeat(64), status, status).update();
    }

    private String createProject(String location) {
        MvcTestResult created = mvc.post()
                .uri("/api/projects")
                .with(csrf())
                .session(owner.session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "revisions-%s", "sourceType": "LOCAL", "sourceLocation": "%s"}
                        """.formatted(UUID.randomUUID(), location.replace("\\", "\\\\")))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return Objects.requireNonNull(created.getResponse().getHeader("Location"))
                .substring("/api/projects/".length());
    }

    private static String lines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).collect(Collectors.joining("\n", "", "\n"));
    }
}
