package dev.swirlit.demo.common.web;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ForwardedHeaderFilter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private static RateLimitFilter ingressFilter() {
        return new RateLimitFilter(true, 1, 100,
                Clock.fixed(Instant.parse("2026-08-27T12:00:30Z"), ZoneOffset.UTC),
                "10.42.0.0/16,fd00:42::/64");
    }

    private static int throughFramework(RateLimitFilter filter, String remote, String forwarded) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users");
        request.setRemoteAddr(remote);
        request.addHeader("X-Forwarded-For", forwarded);
        request.addHeader("Forwarded", "for=198.51.100.99;proto=https;host=spoof.example");
        request.addHeader("X-Real-IP", "198.51.100.98");
        request.addHeader("CF-Connecting-IP", "198.51.100.97");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ForwardedHeaderFilter().doFilter(request, response,
                (wrapped, output) -> filter.doFilter(wrapped, output, new MockFilterChain()));
        return response.getStatus();
    }

    @Test
    void changingForgedPrefixesCannotResetVisitorLimitAcrossDirectAndTunnelIngress() throws Exception {
        RateLimitFilter filter = ingressFilter();
        assertEquals(200, throughFramework(filter, "10.42.1.2",
                "198.51.100.1, 192.0.2.10, 173.245.48.5"));
        assertEquals(429, throughFramework(filter, "10.42.1.3",
                "198.51.100.2, 192.0.2.10, 127.0.0.1"));
        assertEquals(200, throughFramework(filter, "10.42.1.2",
                "198.51.100.1, 192.0.2.11, 173.245.48.5"));
    }

    @Test
    void untrustedSocketCannotChooseItsBucketWithForwardingHeaders() throws Exception {
        RateLimitFilter filter = ingressFilter();
        assertEquals(200, throughFramework(filter, "203.0.113.10", "192.0.2.10, 127.0.0.1"));
        assertEquals(429, throughFramework(filter, "203.0.113.10", "192.0.2.11, 127.0.0.1"));
    }

    @Test
    void malformedTailFallsBackToSocketAndIpv6AliasesShareOneVisitorBucket() throws Exception {
        RateLimitFilter filter = ingressFilter();
        assertEquals(200, throughFramework(filter, "10.42.1.2", "192.0.2.10, invalid"));
        assertEquals(429, throughFramework(filter, "10.42.1.2", "192.0.2.11,"));
        assertEquals(200, throughFramework(filter, "fd00:42::2", "2001:db8::10, ::1"));
        assertEquals(429, throughFramework(filter, "fd00:42::3", "2001:db8:0:0:0:0:0:10, ::1"));
        assertEquals(200, throughFramework(filter, "10.42.1.2", "192.0.2.12"));
        assertEquals(429, throughFramework(filter, "10.42.1.3", "192.0.2.12, 127.0.0.1"));
    }

    @Test
    void rejectsUnboundedOrNonliteralProxyConfiguration() {
        for (String cidr : new String[] {"0.0.0.0/0", "::/0", "10.42.0.0/33", "ingress.example/16"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new RateLimitFilter(true, 1, 100, Clock.systemUTC(), cidr));
        }
    }

    @Test
    void rejectsRequestsAboveConfiguredLimit() throws Exception {
        var registry = new SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(
                true, 1, 100, Clock.fixed(Instant.parse("2026-08-27T12:00:30Z"), ZoneOffset.UTC), "", registry);
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/api/users");
        firstRequest.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();

        filter.doFilter(firstRequest, firstResponse, new MockFilterChain());

        assertEquals(200, firstResponse.getStatus());
        assertEquals("0", firstResponse.getHeader("RateLimit-Remaining"));

        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/api/users");
        secondRequest.setRemoteAddr("192.0.2.10");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertEquals(429, secondResponse.getStatus());
        assertEquals("30", secondResponse.getHeader("Retry-After"));
        assertTrue(secondResponse.getContentAsString().contains("Too many requests"));
        assertTrue(secondResponse.getContentAsString().contains("\"type\":\"/problems/429\""));
        assertEquals(1.0, registry.counter("swirl.rate_limit.rejections").count());
    }

    @Test
    void skipsNonApiRequests() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(
                true, 1, 100, Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNull(response.getHeader("RateLimit-Limit"));
    }
}
