package dev.swirlit.demo.common.web;

import java.io.IOException;
import java.net.InetAddress;
import java.security.Principal;
import java.time.Clock;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int WINDOW_SECONDS = 60;
    private static final String OVERFLOW_CLIENT = "__overflow__";

    private final boolean enabled;
    private final int requestsPerMinute;
    private final int maxTrackedClients;
    private final Clock clock;
    private final List<Subnet> trustedProxies;
    private final ConcurrentHashMap<String, ClientWindow> clients = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupCounter = new AtomicInteger();
    private final Counter rejections;

    @Autowired
    public RateLimitFilter(
            @Value("${app.rate-limit.enabled:false}") boolean enabled,
            @Value("${app.rate-limit.requests-per-minute:120}") int requestsPerMinute,
            @Value("${app.rate-limit.max-tracked-clients:10000}") int maxTrackedClients,
            @Value("${app.rate-limit.trusted-proxy-cidrs:}") String trustedProxyCidrs,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(enabled, requestsPerMinute, maxTrackedClients, Clock.systemUTC(), trustedProxyCidrs,
                meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
    }

    RateLimitFilter(boolean enabled, int requestsPerMinute, int maxTrackedClients, Clock clock) {
        this(enabled, requestsPerMinute, maxTrackedClients, clock, "");
    }

    RateLimitFilter(boolean enabled, int requestsPerMinute, int maxTrackedClients, Clock clock,
            String trustedProxyCidrs) {
        this(enabled, requestsPerMinute, maxTrackedClients, clock, trustedProxyCidrs, new SimpleMeterRegistry());
    }

    RateLimitFilter(boolean enabled, int requestsPerMinute, int maxTrackedClients, Clock clock,
            String trustedProxyCidrs, MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.requestsPerMinute = Math.max(1, requestsPerMinute);
        this.maxTrackedClients = Math.max(100, maxTrackedClients);
        this.clock = clock;
        this.trustedProxies = Arrays.stream(trustedProxyCidrs.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).map(Subnet::parse).toList();
        this.rejections = Counter.builder("swirl.rate_limit.rejections")
                .description("Requests rejected with 429 by the per-client rate limit")
                .register(meterRegistry);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !enabled
                || "OPTIONS".equals(request.getMethod())
                || !path.startsWith("/api/")
                || path.endsWith("/openapi")
                || path.startsWith("/api/docs")
                || path.startsWith("/api/swagger-ui");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long epochSecond = clock.instant().getEpochSecond();
        long window = epochSecond / WINDOW_SECONDS;
        String client = clientKey(request);
        if (clients.size() >= maxTrackedClients && !clients.containsKey(client)) {
            clients.entrySet().removeIf(entry -> entry.getValue().window() < window);
            if (clients.size() >= maxTrackedClients) {
                client = OVERFLOW_CLIENT;
            }
        }

        ClientWindow usage = clients.compute(client, (key, current) -> {
            if (current == null || current.window() != window) {
                return new ClientWindow(window, new AtomicInteger(1));
            }
            current.requests().incrementAndGet();
            return current;
        });

        if ((cleanupCounter.incrementAndGet() & 1023) == 0) {
            clients.entrySet().removeIf(entry -> entry.getValue().window() < window);
        }

        int used = usage.requests().get();
        long reset = WINDOW_SECONDS - (epochSecond % WINDOW_SECONDS);
        response.setHeader("RateLimit-Limit", Integer.toString(requestsPerMinute));
        response.setHeader("RateLimit-Remaining", Integer.toString(Math.max(0, requestsPerMinute - used)));
        response.setHeader("RateLimit-Reset", Long.toString(reset));

        if (used > requestsPerMinute) {
            rejections.increment();
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(reset));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            String requestId = MDC.get("requestId");
            String requestIdProperty = requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"";
            response.getWriter().write("{\"type\":\"/problems/429\","
                    + "\"title\":\"Too many requests\",\"status\":429,"
                    + "\"detail\":\"Request limit exceeded; retry after the indicated delay\""
                    + requestIdProperty + "}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        Principal principal = request.getUserPrincipal();
        if (principal != null) {
            return "user:" + principal.getName();
        }
        // Spring's ForwardedHeaderFilter hides headers and replaces remoteAddr
        // with the leftmost XFF value. Read only the original socket/header here
        // so an Internet-supplied prefix cannot choose the anonymous rate bucket.
        HttpServletRequest original = request;
        while (original instanceof HttpServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest wrapped) {
            original = wrapped;
        }
        InetAddress direct = literalAddress(original.getRemoteAddr());
        if (direct != null && trustedProxies.stream().anyMatch(subnet -> subnet.contains(direct))) {
            String forwarded = String.join(",", Collections.list(original.getHeaders("X-Forwarded-For")));
            String[] chain = forwarded.split(",", -1);
            InetAddress peer = literalAddress(chain[chain.length - 1].trim());
            InetAddress visitor = literalAddress(chain[Math.max(0, chain.length - 2)].trim());
            // Cloudflare appends the visitor; Traefik appends the edge/loopback
            // peer. A single sanitized address covers direct and legacy ingress.
            if (peer != null && visitor != null) {
                return "ip:" + visitor.getHostAddress();
            }
        }
        return "ip:" + (direct == null ? original.getRemoteAddr() : direct.getHostAddress());
    }

    private static InetAddress literalAddress(String value) {
        try {
            return InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record Subnet(byte[] address, int prefix) {
        static Subnet parse(String value) {
            String[] parts = value.split("/", -1);
            InetAddress address = literalAddress(parts[0]);
            if (address == null || parts.length > 2) {
                throw new IllegalArgumentException("Trusted proxy CIDRs must use literal IP addresses");
            }
            int bits = address.getAddress().length * 8;
            int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
            if (prefix < 1 || prefix > bits) {
                throw new IllegalArgumentException("Trusted proxy CIDRs must be bounded networks");
            }
            return new Subnet(address.getAddress(), prefix);
        }

        boolean contains(InetAddress candidate) {
            byte[] other = candidate.getAddress();
            if (other.length != address.length) {
                return false;
            }
            for (int offset = 0; offset < prefix; offset += 8) {
                int mask = 0xff << (8 - Math.min(8, prefix - offset));
                if (((address[offset / 8] ^ other[offset / 8]) & mask) != 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private record ClientWindow(long window, AtomicInteger requests) {
    }
}
