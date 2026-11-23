package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.config.properties.RegistrationRateLimitProperties;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.InMemoryAttemptLimitRepository;
import com.huuhv.foodsndrinks.service.security.RegistrationRateLimitService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.InstantSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** {@code POST /register} bị giới hạn theo IP trước khi form được validate (gồm lần gọi Pwned Passwords). */
class RegistrationRateLimitFilterTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final RegistrationRateLimitFilter filter = new RegistrationRateLimitFilter(
            new RegistrationRateLimitService(new RegistrationRateLimitProperties(2, Duration.ofHours(1)),
                    InMemoryAttemptLimitRepository.factory(InstantSource.system()), auditLogService));

    private MockHttpServletResponse send(String method, String path, String ip) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (chain.getRequest() != null) {
            response.setStatus(200); // đã đi tiếp vào controller
        }
        return response;
    }

    @Test
    void postRegister_overLimit_isRedirectedWithoutProcessing_andAudited() throws Exception {
        assertThat(send("POST", "/register", "203.0.113.5").getStatus()).isEqualTo(200);
        assertThat(send("POST", "/register", "203.0.113.5").getStatus()).isEqualTo(200);

        MockHttpServletResponse throttled = send("POST", "/register", "203.0.113.5");

        assertThat(throttled.getStatus()).isEqualTo(303);
        assertThat(throttled.getHeader("Location")).isEqualTo("/register?throttled=true");
        verify(auditLogService, times(1)).registrationThrottled();
        // IP khác không bị ảnh hưởng
        assertThat(send("POST", "/register", "198.51.100.5").getStatus()).isEqualTo(200);
    }

    @Test
    void getRegisterAndOtherPaths_areNotCounted() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(send("GET", "/register", "203.0.113.6").getStatus()).isEqualTo(200);
            assertThat(send("POST", "/login", "203.0.113.6").getStatus()).isEqualTo(200);
        }
        assertThat(send("POST", "/register", "203.0.113.6").getStatus()).isEqualTo(200);
    }
}
