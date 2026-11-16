package com.devmind.ingestion.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PathRulesTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "target/classes/App.class",
                "module-a/target/generated-sources/annotations/Foo.java",
                "build/libs/app.jar",
                "out/production/App.java",
                "frontend/node_modules/rxjs/index.ts",
                "frontend/dist/main.js",
                ".git/config",
                ".idea/workspace.xml",
                ".gradle/8.10/fileHashes.bin",
                "src/generated/java/Foo.java", // generated is excluded outside a source root
                "generated-sources/openapi/Api.java",
                "src/main/resources/static/dist/app.js" // resources aren't a Java source root
            })
    void excludesBuildDependencyIdeAndGeneratedDirectoriesAtAnyDepth(String path) {
        assertThat(PathRules.inExcludedDirectory(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "pom.xml",
                "src/main/java/com/acme/App.java",
                "src/main/java/com/acme/build/Builder.java", // a package named build is code
                "src/test/java/com/acme/out/OutputTest.java",
                "service/src/main/kotlin/com/acme/generated/Mapper.kt",
                "docs/building.md", // file names and look-alike directories don't match
                "targets/readme.md",
                "build.gradle",
                "src/main/resources/application.yml"
            })
    void keepsSourceAndLookAlikePaths(String path) {
        assertThat(PathRules.inExcludedDirectory(path)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                ".env",
                "deploy/.env.production",
                "certs/server.pem",
                "certs/server.KEY",
                "keystore.p12",
                "src/main/resources/truststore.jks",
                "app.keystore",
                "infra/terraform.tfstate",
                "home/id_rsa",
                "home/id_rsa.pub",
                "home/id_ed25519",
                "aws/credentials",
                "config/credentials.json",
                "config/credentials.yml"
            })
    void recognizesFilesThatTypicallyHoldSecrets(String path) {
        assertThat(PathRules.isSecretFile(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "src/main/java/com/acme/Credentials.java", // case-sensitive: a class, not a credentials file
                "src/app/auth/credentials.service.ts", // code is exempt from credentials*
                "src/main/kotlin/credentials.kt",
                "docs/environment.md",
                "src/main/java/com/acme/KeyService.java",
                "README.md"
            })
    void keepsCodeAndOrdinaryFiles(String path) {
        assertThat(PathRules.isSecretFile(path)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
        "src/main/java/App.java,       java",
        "src/main/kotlin/App.kt,       kotlin",
        "build.gradle.kts,             kotlin",
        "pom.xml,                      xml",
        "application.yml,              yaml",
        "application.YAML,             yaml",
        "application.properties,       properties",
        "db/migration/V1__init.sql,    sql",
        "README.md,                    markdown",
        "build.gradle,                 gradle",
        "package.json,                 json",
        "src/app/app.component.ts,     typescript",
        "src/index.html,               html",
        "src/styles.scss,              scss"
    })
    void mapsAllowListedExtensionsToLanguages(String path, String language) {
        assertThat(PathRules.language(path)).contains(language);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dockerfile", "mvnw", ".gitignore", "logo.png", "app.js", "notes.txt", "src/App.class"})
    void rejectsExtensionsOffTheAllowList(String path) {
        assertThat(PathRules.language(path)).isEmpty();
    }
}
