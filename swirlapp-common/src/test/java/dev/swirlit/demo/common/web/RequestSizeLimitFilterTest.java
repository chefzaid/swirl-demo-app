package dev.swirlit.demo.common.web;

import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(DataSize.ofBytes(10));

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
        assertThat(response.getContentAsString()).contains("\"title\":\"Payload too large\"", "\"status\":413");
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
        filter.doFilter(chunked(new byte[10]), new MockHttpServletResponse(), withinLimit);
        try (InputStream body = withinLimit.getRequest().getInputStream()) {
            assertThat(body.readAllBytes()).hasSize(10);
        }

        MockFilterChain overLimit = new MockFilterChain();
        filter.doFilter(chunked(new byte[11]), new MockHttpServletResponse(), overLimit);
        InputStream body = overLimit.getRequest().getInputStream();
        assertThat(body).isSameAs(overLimit.getRequest().getInputStream());
        assertThat(body.read()).isZero();
        assertThatThrownBy(body::readAllBytes)
                .isInstanceOf(PayloadTooLargeException.class)
                .hasMessage("Request body exceeds 10 bytes");
    }

    private static HttpServletRequest chunked(byte[] content) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/users") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(content);
        return request;
    }
}
