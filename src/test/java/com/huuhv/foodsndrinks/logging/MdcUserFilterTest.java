package com.huuhv.foodsndrinks.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcUserFilterTest {

    private final MdcUserFilter filter = new MdcUserFilter();
    private final AtomicReference<String> mdcInChain = new AtomicReference<>();
    private final FilterChain chain = (req, res) -> mdcInChain.set(MDC.get(MdcUserFilter.MDC_KEY));

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void authenticatedUser_isPutInMdc_andRemovedAfterwards() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(mdcInChain.get()).isEqualTo("alice");
        assertThat(MDC.get(MdcUserFilter.MDC_KEY)).isNull();
    }

    @Test
    void anonymousUser_isNotPutInMdc() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(mdcInChain.get()).isNull();
    }

    @Test
    void usernameWithControlCharacters_isSanitized() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("bob\r\nfake", null, List.of()));

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(mdcInChain.get()).isEqualTo("bob_fake");
    }
}
