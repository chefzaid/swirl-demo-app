package dev.swirlit.demo.common.exception;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerLoggingTest {

    private static final String REJECTED_EMAIL = "ada@example.test";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void constraintViolationLogsTheSqlStateButNotTheRejectedValues(CapturedOutput output) {
        var cause = new SQLException("duplicate key: Key (email)=(" + REJECTED_EMAIL + ") already exists", "23505");

        ProblemDetail problem = handler.handleConflict(new DataIntegrityViolationException("insert failed", cause));

        assertThat(output).contains("Database constraint violation sqlState=23505").doesNotContain(REJECTED_EMAIL);
        assertThat(problem.getDetail()).doesNotContain(REJECTED_EMAIL);
    }

    @Test
    void constraintViolationWithoutSqlCauseStillLogsNoDetails(CapturedOutput output) {
        handler.handleConflict(new DataIntegrityViolationException("Key (email)=(" + REJECTED_EMAIL + ")"));

        assertThat(output).contains("sqlState=null").doesNotContain(REJECTED_EMAIL);
    }
}
