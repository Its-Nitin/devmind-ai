package com.devmind.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void echoesWellFormedIncomingIdAndExposesItInMdcDuringTheRequest() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-12345-XYZ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        // When
        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        // Then
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-12345-XYZ");
        assertThat(seenInChain.get()).isEqualTo("abc-12345-XYZ");
    }

    @Test
    void generatesUuidWhenHeaderIsAbsent() throws Exception {
        // Given
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});

        // Then
        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generated).isNotNull();
        assertThat(UUID.fromString(generated)).hasToString(generated);
    }

    @Test
    void replacesMalformedIdInsteadOfTrustingIt() throws Exception {
        // Given: a value that could forge log lines
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "short\nINFO forged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // When
        filter.doFilter(request, response, (req, res) -> {});

        // Then
        String used = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(used).isNotEqualTo("short\nINFO forged");
        assertThat(UUID.fromString(used)).hasToString(used);
    }

    @Test
    void clearsMdcAfterTheRequestEvenWhenTheChainFails() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // When
        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("downstream failure");
            });
        } catch (Exception expected) {
            // the failure itself is not under test
        }

        // Then
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
