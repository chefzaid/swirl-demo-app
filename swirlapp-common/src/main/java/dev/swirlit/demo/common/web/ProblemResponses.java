package dev.swirlit.demo.common.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Writes a Problem Details body from a servlet filter, where no controller advice runs. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    /**
     * Writes a problem with the request ID, in the same shape and content type as the controller advice. The
     * request ID is safe to embed because {@code RequestIdFilter} only admits {@code [A-Za-z0-9._:-]}; {@code title}
     * and {@code detail} must be constant JSON-safe text.
     */
    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        String requestId = MDC.get("requestId");
        String requestIdProperty = requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"";
        byte[] body = ("{\"type\":\"/problems/" + status + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\"" + requestIdProperty + "}").getBytes(StandardCharsets.UTF_8);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
