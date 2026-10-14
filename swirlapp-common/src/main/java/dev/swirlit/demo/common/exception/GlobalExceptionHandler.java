package dev.swirlit.demo.common.exception;

import java.net.URI;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String VALIDATION_TITLE = "Validation failed";
    private static final String INVALID_REQUEST = "The request is invalid";
    private static final String VIOLATIONS_PROPERTY = "violations";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    ProblemDetail handleEntityNotFound(EntityNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> violations = new LinkedHashMap<>();
        for (var error : exception.getBindingResult().getAllErrors()) {
            String field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            violations.put(field, error.getDefaultMessage());
        }

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, VALIDATION_TITLE, INVALID_REQUEST);
        detail.setProperty(VIOLATIONS_PROPERTY, violations);
        return detail;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            String parameter = result.getMethodParameter().getParameterName();
            String message = result.getResolvableErrors().isEmpty()
                    ? "is invalid"
                    : result.getResolvableErrors().getFirst().getDefaultMessage();
            violations.put(parameter == null ? "parameter" : parameter, message);
        });

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, VALIDATION_TITLE, INVALID_REQUEST);
        detail.setProperty(VIOLATIONS_PROPERTY, violations);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                violations.put(violation.getPropertyPath().toString(), violation.getMessage()));

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, VALIDATION_TITLE, INVALID_REQUEST);
        detail.setProperty(VIOLATIONS_PROPERTY, violations);
        return detail;
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail handleMalformedRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "The request syntax or parameter types are invalid");
    }

    @ExceptionHandler(ResourceConflictException.class)
    ProblemDetail handleResourceConflict(ResourceConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Data conflict", exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleConflict(DataIntegrityViolationException exception) {
        // Database messages quote the rejected values, which can be personal data; log only the SQL state.
        log.warn("Database constraint violation sqlState={}", sqlState(exception));
        return problem(HttpStatus.CONFLICT, "Data conflict", "A record with the same unique value already exists");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", "The requested resource does not exist");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", "The HTTP method is not supported here");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException exception) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", "Use a supported Content-Type");
    }

    /** Keeps the status of a deliberate {@code ResponseStatusException}, such as actuator's bad-request errors. */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null || status.is5xxServerError()) {
            return handleUnexpected(exception);
        }
        String detail = exception.getReason() == null ? status.getReasonPhrase() : exception.getReason();
        return problem(status, status.getReasonPhrase(), detail);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "The caller lacks the role this operation requires");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "The request could not be completed");
    }

    private static String sqlState(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(title);
        detail.setType(URI.create("/problems/" + status.value()));
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            detail.setProperty("requestId", requestId);
        }
        return detail;
    }
}
