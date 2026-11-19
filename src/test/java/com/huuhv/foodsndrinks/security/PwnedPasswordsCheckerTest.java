package com.huuhv.foodsndrinks.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PwnedPasswordsCheckerTest {

    // SHA-1("password") = 5BAA6 1E4C9B93F3F0682250B6CF8331B7EE68FD8
    private static final String URL = "https://api.pwnedpasswords.com/range/5BAA6";
    private static final String SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";

    private MockRestServiceServer server;
    private PwnedPasswordsChecker checker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        checker = new PwnedPasswordsChecker(builder.build());
    }

    @Test
    void sendsOnlyHashPrefix_andDetectsLeakedPassword() {
        server.expect(requestTo(URL))
                .andExpect(header("Add-Padding", "true"))
                .andRespond(withSuccess("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n" + SUFFIX + ":52256179\r\n",
                        MediaType.TEXT_PLAIN));

        assertThat(checker.check("password").isCompromised()).isTrue();
        server.verify();
    }

    @Test
    void paddingEntryWithZeroCount_isNotALeak() {
        server.expect(requestTo(URL)).andRespond(withSuccess(SUFFIX + ":0\r\n", MediaType.TEXT_PLAIN));

        assertThat(checker.check("password").isCompromised()).isFalse();
    }

    @Test
    void serviceError_isPropagated_soCallerCanFailOpenAndLog() {
        server.expect(requestTo(URL)).andRespond(withServerError());

        assertThatThrownBy(() -> checker.check("password")).isInstanceOf(RestClientException.class);
    }
}
