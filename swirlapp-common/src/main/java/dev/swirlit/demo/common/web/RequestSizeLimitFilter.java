package dev.swirlit.demo.common.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.CorsProcessor;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies larger than {@code app.request-size.max} (64 KB by default):
 * <ul>
 *   <li>a declared {@code Content-Length} over the limit gets {@code 413} before any byte is read;</li>
 *   <li>a body without a declared length (chunked) is counted as the application reads it, and reading past the
 *       limit fails with {@link PayloadTooLargeException}, answered with {@code 413};</li>
 *   <li>a form or multipart body without a declared length gets {@code 411}, because the container parses those
 *       through its own stream, out of this filter's reach, and no endpoint accepts them.</li>
 * </ul>
 * It runs before Spring Security, so it applies the service's CORS rules itself; otherwise a browser could not
 * read the rejection. The ingress applies its own, larger limit in front of this one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private static final CorsProcessor CORS = new DefaultCorsProcessor();

    private final long maxBytes;
    private final CorsConfigurationSource cors;

    @Autowired
    public RequestSizeLimitFilter(
            @Value("${app.request-size.max:64KB}") DataSize max,
            @Qualifier("corsConfigurationSource") ObjectProvider<CorsConfigurationSource> cors) {
        this(max, cors.getIfAvailable());
    }

    RequestSizeLimitFilter(DataSize max, CorsConfigurationSource cors) {
        Assert.isTrue(max != null && max.toBytes() > 0,
                "app.request-size.max must be a positive data size such as 64KB");
        this.maxBytes = max.toBytes();
        this.cors = cors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            reject(request, response, 413, PayloadTooLargeException.TITLE, PayloadTooLargeException.DETAIL);
            return;
        }
        if (declared < 0 && isFormOrMultipart(request)) {
            reject(request, response, 411, "Length required",
                    "Form and multipart bodies must declare their Content-Length");
            return;
        }
        try {
            chain.doFilter(declared < 0 ? new LimitedRequest(request, maxBytes) : request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            // A filter read past the limit outside Spring MVC, where the controller advice cannot answer.
            if (!PayloadTooLargeException.causes(exception) || response.isCommitted()) {
                throw exception;
            }
            response.resetBuffer();
            reject(request, response, 413, PayloadTooLargeException.TITLE, PayloadTooLargeException.DETAIL);
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String title,
            String detail) throws IOException {
        CorsConfiguration configuration = cors == null ? null : cors.getCorsConfiguration(request);
        if (configuration != null && !CORS.processRequest(configuration, request, response)) {
            return;
        }
        ProblemResponses.write(response, status, title, detail);
    }

    private static boolean isFormOrMultipart(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_FORM_URLENCODED.includes(type) || "multipart".equalsIgnoreCase(type.getType());
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;
        private BufferedReader reader;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called for this request");
            }
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                if (stream != null) {
                    throw new IllegalStateException("getInputStream() has already been called for this request");
                }
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
                reader = new BufferedReader(new InputStreamReader(
                        new LimitedInputStream(super.getInputStream(), maxBytes), charset));
            }
            return reader;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int next = delegate.read();
            if (next >= 0) {
                count(1);
            }
            return next;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws PayloadTooLargeException {
            read += bytes;
            if (read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
