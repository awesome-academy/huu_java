package com.huuhv.foodsndrinks.logging;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<String> mdcInChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> mdcInChain.set(MDC.get(RequestIdFilter.MDC_KEY));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void validHeader_isKept_inMdcAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, "abc-123-XYZ");

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).isEqualTo("abc-123-XYZ");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123-XYZ");
    }

    @Test
    void missingHeader_generatesUuid() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/menu"), response, chain);

        String generated = response.getHeader(RequestIdFilter.HEADER);
        assertThatCode(() -> UUID.fromString(generated)).doesNotThrowAnyException();
        assertThat(mdcInChain.get()).isEqualTo(generated);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "has space",
            "inject\r\nevent=LOGIN_SUCCESS",
            "semi;colon",
            "unicode-ký-tự",
            // 65 ký tự — vượt giới hạn 64
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    })
    void invalidHeader_isReplacedByUuid(String invalid) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, invalid);

        filter.doFilter(request, response, chain);

        String requestId = response.getHeader(RequestIdFilter.HEADER);
        assertThat(requestId).isNotEqualTo(invalid);
        assertThatCode(() -> UUID.fromString(requestId)).doesNotThrowAnyException();
        assertThat(mdcInChain.get()).isEqualTo(requestId);
    }

    @Test
    void headerOf64Chars_isAccepted() throws Exception {
        String max = "a".repeat(64);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, max);

        filter.doFilter(request, response, chain);

        assertThat(mdcInChain.get()).isEqualTo(max);
    }

    @Test
    void mdcIsCleared_afterRequest_evenWhenChainThrows() {
        FilterChain failing = (req, res) -> { throw new IllegalStateException("boom"); };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/menu"), response, failing);
        } catch (Exception ignored) {
            // expected
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void errorDispatch_reusesRequestIdOfOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        filter.doFilter(request, response, chain);
        String original = mdcInChain.get();

        // Tomcat dispatch lại cùng request object tới /error sau khi request gốc kết thúc
        request.setDispatcherType(DispatcherType.ERROR);
        mdcInChain.set(null);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(mdcInChain.get()).isEqualTo(original);
    }
}
