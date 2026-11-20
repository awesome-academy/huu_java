package com.huuhv.foodsndrinks.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestUtilsTest {

    @Test
    void ipv4_isKeptAsIs() {
        assertThat(RequestUtils.rateLimitKey("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6_isGroupedBy64Network() {
        String key = RequestUtils.rateLimitKey("2001:db8:1:2::1");

        assertThat(key).isEqualTo("20010db800010002::/64");
        assertThat(RequestUtils.rateLimitKey("2001:0DB8:0001:0002:ffff:ffff:ffff:ffff")).isEqualTo(key);
        assertThat(RequestUtils.rateLimitKey("2001:db8:1:3::1")).isNotEqualTo(key);
    }

    @Test
    void ipv4MappedIpv6_isTreatedAsIpv4() {
        assertThat(RequestUtils.rateLimitKey("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void missingOrNonLiteralValue_neverTriggersDnsLookup() {
        assertThat(RequestUtils.rateLimitKey(null)).isEqualTo("-");
        assertThat(RequestUtils.rateLimitKey("example.com")).isEqualTo("example.com");
        assertThat(RequestUtils.rateLimitKey("evil:host.example")).isEqualTo("evil:host.example");
    }
}
