package dev.swirlit.demo.common.exception;

import dev.swirlit.demo.common.web.PayloadTooLargeException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void keepsTheClientErrorOfAResponseStatusException() {
        ProblemDetail problem = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a dead-letter topic of this service: x"));

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("Not a dead-letter topic of this service: x");
        assertThat(handler.handleResponseStatus(new ResponseStatusException(HttpStatus.CONFLICT)).getDetail())
                .isEqualTo("Conflict");
    }

    @Test
    void hidesTheReasonOfServerAndUnknownErrors() {
        for (var status : new HttpStatusCode[] {HttpStatus.SERVICE_UNAVAILABLE, HttpStatusCode.valueOf(599)}) {
            ProblemDetail problem = handler.handleResponseStatus(
                    new ResponseStatusException(status, "connection to db-7.internal refused"));

            assertThat(problem.getStatus()).isEqualTo(500);
            assertThat(problem.getDetail()).isEqualTo("The request could not be completed");
        }
    }

    @Test
    void deniedAccessIsForbidden() {
        ProblemDetail problem = handler.handleAccessDenied(new AccessDeniedException("role missing"));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getDetail()).isEqualTo("The caller lacks the role this operation requires");
    }

    @Test
    void anOversizedBodyIsTooLargeRatherThanMalformed() {
        var unreadable = new HttpMessageNotReadableException("I/O error",
                new PayloadTooLargeException(65536), new MockHttpInputMessage(new byte[0]));

        assertThat(handler.handleMalformedRequest(unreadable).getStatus()).isEqualTo(413);
        assertThat(handler.handleMalformedRequest(new HttpMessageNotReadableException("bad json",
                new MockHttpInputMessage(new byte[0]))).getStatus()).isEqualTo(400);
    }
}
