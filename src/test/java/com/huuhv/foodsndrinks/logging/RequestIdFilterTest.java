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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final AtomicReference<Map<String, String>> mdcInChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        mdcInChain.set(copy == null ? new HashMap<>() : copy);
    };

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private String requestIdInChain() {
        return mdcInChain.get().get(RequestIdFilter.MDC_KEY);
    }

    private String clientRequestIdInChain() {
        return mdcInChain.get().get(RequestIdFilter.CLIENT_MDC_KEY);
    }

    @Test
    void requestId_isAlwaysServerGenerated_andReturnedInResponse() throws Exception {
        filter.doFilter(new MockHttpServletRequest("GET", "/menu"), response, chain);

        String requestId = response.getHeader(RequestIdFilter.HEADER);
        assertThatCode(() -> UUID.fromString(requestId)).doesNotThrowAnyException();
        assertThat(requestIdInChain()).isEqualTo(requestId);
        assertThat(clientRequestIdInChain()).isNull();
    }

    @Test
    void validClientHeader_isKeptAsClientRequestId_butNotUsedAsRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, "abc-123-XYZ");

        filter.doFilter(request, response, chain);

        assertThat(clientRequestIdInChain()).isEqualTo("abc-123-XYZ");
        assertThat(requestIdInChain()).isNotEqualTo("abc-123-XYZ");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(requestIdInChain());
    }

    @Test
    void sameClientHeaderOnTwoRequests_givesDifferentRequestIds() throws Exception {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/menu");
        first.addHeader(RequestIdFilter.HEADER, "reused-id");
        filter.doFilter(first, response, chain);
        String firstId = requestIdInChain();

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/menu");
        second.addHeader(RequestIdFilter.HEADER, "reused-id");
        filter.doFilter(second, new MockHttpServletResponse(), chain);

        assertThat(requestIdInChain()).isNotEqualTo(firstId);
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
    void invalidClientHeader_isIgnored(String invalid) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, invalid);

        filter.doFilter(request, response, chain);

        assertThat(clientRequestIdInChain()).isNull();
        assertThatCode(() -> UUID.fromString(requestIdInChain())).doesNotThrowAnyException();
    }

    @Test
    void clientHeaderOf64Chars_isAccepted() throws Exception {
        String max = "a".repeat(64);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, max);

        filter.doFilter(request, response, chain);

        assertThat(clientRequestIdInChain()).isEqualTo(max);
    }

    @Test
    void mdcIsCleared_afterRequest_evenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, "abc");
        FilterChain failing = (req, res) -> { throw new IllegalStateException("boom"); };

        try {
            filter.doFilter(request, response, failing);
        } catch (Exception ignored) {
            // expected
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
        assertThat(MDC.get(RequestIdFilter.CLIENT_MDC_KEY)).isNull();
    }

    @Test
    void errorDispatch_reusesIdsOfOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/menu");
        request.addHeader(RequestIdFilter.HEADER, "client-1");
        filter.doFilter(request, response, chain);
        String original = requestIdInChain();

        // Tomcat dispatch lại cùng request object tới /error sau khi request gốc kết thúc
        request.setDispatcherType(DispatcherType.ERROR);
        mdcInChain.set(null);
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(requestIdInChain()).isEqualTo(original);
        assertThat(clientRequestIdInChain()).isEqualTo("client-1");
    }
}
