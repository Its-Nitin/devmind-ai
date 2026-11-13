package com.devmind;

import static org.assertj.core.api.Assertions.assertThat;

import com.devmind.identity.internal.application.UserRegistrationService;
import com.devmind.support.IntegrationTest;
import com.devmind.support.TestUsers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * API-10: {@code backend/openapi.json} is the committed API contract, and the frontend's types are generated from it.
 * This test fails when the running API no longer matches the file. After an intended API change, regenerate it with
 * {@value #REGENERATE} and commit the diff together with the change.
 */
@IntegrationTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final String REGENERATE = "./mvnw -q test -Dtest=OpenApiContractTest -DupdateOpenApi=true";
    private static final Path CONTRACT = Path.of("openapi.json"); // Surefire runs in the backend directory

    private final MockMvcTester mvc;
    private final JsonMapper jsonMapper;
    private final TestUsers users;

    @Autowired
    OpenApiContractTest(MockMvcTester mvc, JsonMapper jsonMapper, UserRegistrationService registration) {
        this.mvc = mvc;
        this.jsonMapper = jsonMapper;
        this.users = new TestUsers(mvc, registration);
    }

    @Test
    void committedContractMatchesTheApi() throws IOException {
        // Given: the docs need a session, like the rest of the API
        MvcTestResult response =
                mvc.get().uri("/v3/api-docs").session(users.signIn().session()).exchange();
        assertThat(response).hasStatusOk();

        // When
        String generated = prettyPrint(response.getResponse().getContentAsString(StandardCharsets.UTF_8));

        // Then
        if (Boolean.parseBoolean(System.getProperty("updateOpenApi"))) {
            Files.writeString(CONTRACT, generated);
            return;
        }
        assertThat(CONTRACT)
                .as("missing contract; generate it with " + REGENERATE)
                .exists();
        assertThat(Files.readString(CONTRACT).replace("\r\n", "\n"))
                .as("backend/openapi.json is stale (API-10). If the API change is intended, run " + REGENERATE
                        + " and commit the result")
                .isEqualTo(generated);
    }

    /** LF line endings whatever the OS, matching .gitattributes, and a final newline. */
    private String prettyPrint(String json) {
        return jsonMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(jsonMapper.readTree(json))
                        .replace("\r\n", "\n")
                + "\n";
    }
}
