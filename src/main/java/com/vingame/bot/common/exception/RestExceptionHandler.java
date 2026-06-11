package com.vingame.bot.common.exception;

import com.vingame.bot.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Single point of truth for mapping service-layer exceptions to HTTP responses.
 * <p>
 * Replaces the per-controller {@code try/catch} ladders that previously dropped
 * the underlying error message on the floor and returned an empty 4xx/5xx body.
 * Every non-2xx response (except {@link ResourceNotFoundException}, which keeps
 * its bodyless 404 contract for backward compatibility with existing UI code)
 * carries a structured {@link ErrorResponse} {@code {type, msg}} envelope.
 *
 * <h2>Mapping</h2>
 * <ul>
 *   <li>{@link ResourceNotFoundException} &rarr; 404 (no body)</li>
 *   <li>{@link BadRequestException}, {@link IllegalArgumentException},
 *       {@link MethodArgumentTypeMismatchException},
 *       {@link HttpMessageNotReadableException} &rarr; 400 with body</li>
 *   <li>{@link UpstreamGatewayException} (and subclasses) &rarr; 502 with body;
 *       {@code type} comes from {@link UpstreamGatewayException#getType()}</li>
 *   <li>{@link IllegalStateException} &rarr; 500 with body (transitional, see
 *       plan AD-8)</li>
 *   <li>Anything else &rarr; 500 with body</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Void> handleNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        // Bodyless 404 — see plan AD-5. Logged at INFO so a missing resource
        // doesn't pollute ERROR-level dashboards.
        log.info("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e, HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Bad request", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Bad request", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                             HttpServletRequest request) {
        String msg = String.format("Invalid value for parameter '%s': '%s'",
                e.getName(), e.getValue());
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), msg);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Bad request", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException e,
                                                               HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Bad request", "Malformed request body"));
    }

    /**
     * Catches all {@link UpstreamGatewayException} subclasses
     * ({@link UpstreamRegistrationException}, {@link UpstreamLoginException},
     * etc.) and maps to 502 with the subclass-defined {@code type}.
     */
    @ExceptionHandler(UpstreamGatewayException.class)
    public ResponseEntity<ErrorResponse> handleUpstream(UpstreamGatewayException e, HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse(e.getType(), e.getMessage()));
    }

    /**
     * Transitional handler — see plan AD-8. Many {@link IllegalStateException}
     * call sites in the codebase today are genuine programming errors
     * ("not initialized"); a few are misclassified validation failures or
     * loud-restart signals. Map to 500 with body so the operator can see the
     * cause; future commits should re-type the meaningful ones.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error", e.getMessage()));
    }

    /**
     * Terminal fallback. Logs the full stacktrace because, by definition, we
     * don't have a specific mapping for this type and the operator will need
     * the trace to diagnose.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e, HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage(), e);
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error", msg));
    }
}
