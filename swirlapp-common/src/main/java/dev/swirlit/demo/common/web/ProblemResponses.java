package dev.swirlit.demo.common.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.MediaType;

/** Writes a Problem Details body from a servlet filter, where no controller advice runs. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    /** Writes a problem with the request ID; {@code title} and {@code detail} must be constant JSON-safe text. */
    static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String requestId = MDC.get("requestId");
        String requestIdProperty = requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"";
        response.getWriter().write("{\"type\":\"/problems/" + status + "\",\"title\":\"" + title + "\",\"status\":"
                + status + ",\"detail\":\"" + detail + "\"" + requestIdProperty + "}");
    }
}
