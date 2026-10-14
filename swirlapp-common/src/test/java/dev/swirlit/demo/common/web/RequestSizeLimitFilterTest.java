package dev.swirlit.demo.common.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(DataSize.ofBytes(10), (CorsConfigurationSource) null);

    @Test
    void rejectsADeclaredBodyOverTheLimitBeforeReadingIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).isEqualTo(
                "{\"type\":\"/problems/413\",\"title\":\"Payload too large\",\"status\":413,"
                        + "\"detail\":\"The request body exceeds the size limit\"}");
        assertThat(response.getContentLength()).isEqualTo(response.getContentAsByteArray().length);
    }

    @Test
    void passesADeclaredBodyWithinTheLimitUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users");
        request.setContent(new byte[10]);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void countsABodyOfUnknownLengthAsItIsRead() throws Exception {
        MockFilterChain withinLimit = new MockFilterChain();
        filter.doFilter(chunked(new byte[10], "application/json"), new MockHttpServletResponse(), withinLimit);
        try (InputStream body = withinLimit.getRequest().getInputStream()) {
            assertThat(body.readAllBytes()).hasSize(10);
        }

        MockFilterChain overLimit = new MockFilterChain();
        filter.doFilter(chunked(new byte[11], "application/json"), new MockHttpServletResponse(), overLimit);
        InputStream body = overLimit.getRequest().getInputStream();
        assertThat(body).isSameAs(overLimit.getRequest().getInputStream());
        assertThat(body.read()).isZero();
        assertThatThrownBy(body::readAllBytes)
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessage("Request body exceeds 10 bytes");
    }

    @Test
    void limitsTheReaderAsWellAsTheStream() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(chunked("abcdefghijk".getBytes(), "text/plain"), new MockHttpServletResponse(), chain);

        BufferedReader reader = chain.getRequest().getReader();
        assertThat(reader).isSameAs(chain.getRequest().getReader());
        assertThatThrownBy(reader::readLine).isInstanceOf(PayloadTooLargeException.class);
        assertThatThrownBy(chain.getRequest()::getInputStream).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/x-www-form-urlencoded", "multipart/form-data; boundary=b",
            "MULTIPART/mixed; boundary=b"})
    void requiresADeclaredLengthForBodiesTheContainerParsesItself(String contentType) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked(new byte[1], contentType), response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(411);
        assertThat(response.getContentAsString()).contains("\"title\":\"Length required\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "not a media type"})
    void otherBodiesOfUnknownLengthPass(String contentType) throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(chunked(new byte[1], contentType), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void answersAnOversizedReadByALaterFilterWith413() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain readsEverything = (request, ignored) -> {
            try {
                request.getInputStream().readAllBytes();
            } catch (IOException exception) {
                throw new ServletException("filter failed", exception);
            }
        };

        filter.doFilter(chunked(new byte[11], "application/json"), response, readsEverything);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"status\":413");
    }

    @Test
    void rethrowsOtherFailuresAndFailuresAfterTheResponseWasCommitted() {
        FilterChain fails = (request, response) -> {
            throw new ServletException("unrelated");
        };
        assertThatThrownBy(() -> filter.doFilter(chunked(new byte[1], "application/json"),
                new MockHttpServletResponse(), fails)).hasMessage("unrelated");

        MockHttpServletResponse committed = new MockHttpServletResponse();
        FilterChain commitsThenReads = (request, response) -> {
            response.flushBuffer();
            request.getInputStream().readAllBytes();
        };
        assertThatThrownBy(() -> filter.doFilter(chunked(new byte[11], "application/json"), committed,
                commitsThenReads)).isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void appliesTheServiceCorsRulesSoBrowsersCanReadTheRejection() throws Exception {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("https://app.example.test"));
        configuration.setAllowedMethods(List.of("POST"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        var withCors = new RequestSizeLimitFilter(DataSize.ofBytes(10), source);

        MockHttpServletResponse allowed = new MockHttpServletResponse();
        withCors.doFilter(fromOrigin("https://app.example.test"), allowed, new MockFilterChain());
        assertThat(allowed.getStatus()).isEqualTo(413);
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://app.example.test");

        MockHttpServletResponse foreign = new MockHttpServletResponse();
        withCors.doFilter(fromOrigin("https://evil.example.test"), foreign, new MockFilterChain());
        assertThat(foreign.getStatus()).isEqualTo(403);
        assertThat(foreign.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void refusesALimitThatIsNotPositive(long bytes) {
        assertThatThrownBy(() -> new RequestSizeLimitFilter(DataSize.ofBytes(bytes), (CorsConfigurationSource) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("app.request-size.max must be a positive data size such as 64KB");
        assertThatThrownBy(() -> new RequestSizeLimitFilter(null, (CorsConfigurationSource) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HttpServletRequest fromOrigin(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users");
        request.addHeader("Origin", origin);
        request.setContent(new byte[11]);
        return request;
    }

    private static HttpServletRequest chunked(byte[] content, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(content);
        request.setContentType(contentType);
        return request;
    }
}
