package com.devmind.indexing.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdentifierSplitterTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // the plan's examples (M2 step 6.1)
                "findOwnerById|find owner by id",
                "HTTPServerError|http server error",
                "OWNER_ID|owner id",
                "parseXML2Json|parse xml 2 json",
                "owner-details|owner details",
                // and around them
                "getHTTPResponse|get http response",
                "IOException|io exception",
                "petType2|pet type 2",
                "v2Api|v 2 api",
                "snake_case_name|snake case name",
                "$jacocoData|jacoco data",
                "a.b.c|a b c",
                "Owner|owner",
                "id|id",
                "URL|url",
                "größeBerechnen|größe berechnen"
            })
    void splitsIdentifiersIntoLowercaseWords(String identifier, String words) {
        assertThat(IdentifierSplitter.split(identifier)).isEqualTo(words);
    }

    @Test
    void splitsNothingOutOfSeparatorsAlone() {
        assertThat(IdentifierSplitter.split("__")).isEmpty();
        assertThat(IdentifierSplitter.split("")).isEmpty();
    }

    @Test
    void collectsTheNewTermsOfEveryIdentifierInTextOnceInOrder() {
        // When
        String terms = IdentifierSplitter.terms("Owner owner = ownerService.findById(id); ownerService.save(owner);");

        // Then: single words add nothing; ownerService appears once
        assertThat(terms).isEqualTo("owner service find by id");
    }

    @Test
    void findsKebabCaseAndConstantsInText() {
        assertThat(IdentifierSplitter.terms("spring.datasource.max-pool-size=10 # MAX_POOL"))
                .isEqualTo("max pool size max pool");
    }
}
