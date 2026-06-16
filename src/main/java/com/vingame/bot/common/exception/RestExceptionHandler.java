package com.vingame.bot.common.exception;

import com.vingame.bot.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single point of truth for mapping service-layer exceptions to HTTP responses.
 * <p>
 * Replaces the per-controller {@code try/catch} ladders that previously dropped
 * the underlying error message on the floor and returned an empty 4xx/5xx body.
 * Every non-2xx response (except {@link ResourceNotFoundException}, which keeps
 * its bodyless 404 contract for backward compatibility with existing UI code)
 * carries a structured {@link ErrorResponse} {@code {type, msg}} envelope.
 *
 * <h2>Why extend {@link ResponseEntityExceptionHandler}</h2>
 * Spring's default exception machinery maps a family of servlet exceptions to
 * first-class 4xx responses ({@code HttpRequestMethodNotSupportedException} →
 * 405, {@code HttpMediaTypeNotSupportedException} → 415, etc.). An advice that
 * only declared a terminal {@code @ExceptionHandler(Exception.class)} would
 * intercept those and silently turn them into 500s. Extending
 * {@link ResponseEntityExceptionHandler} preserves the default status codes;
 * we only override {@link #handleExceptionInternal} to reformat the body into
 * our {@code {type, msg}} envelope.
 *
 * <h2>Mapping</h2>
 * <ul>
 *   <li>{@link ResourceNotFoundException} &rarr; 404 (no body)</li>
 *   <li>{@link BadRequestException}, {@link IllegalArgumentException},
 *       {@link MethodArgumentTypeMismatchException},
 *       {@link HttpMessageNotReadableException} &rarr; 400 with body</li>
 *   <li>{@link UpstreamGatewayException} (and subclasses) &rarr; 502 with body;
 *       {@code type} comes from {@link UpstreamGatewayException#getType()}</li>
 *   <li>{@link IllegalStateException} &rarr; 500 with sanitised body
 *       (transitional, see plan AD-8)</li>
 *   <li>Spring-managed exceptions ({@code HttpRequestMethodNotSupportedException},
 *       {@code HttpMediaTypeNotSupportedException},
 *       {@code MissingServletRequestParameterException}, etc.) &rarr; preserved
 *       statuses (405/415/400/…) with body reformatted to {@code {type, msg}}
 *       via {@link #handleExceptionInternal}.</li>
 *   <li>Anything else &rarr; 500 with sanitised body (never echoes the raw
 *       exception message to the client — Mongo hostnames, Spring wiring
 *       failures, JDK HttpClient infra details are all server-log-only)</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Fixed, operator-safe message used in the 500 fallback. The raw
     * {@code e.getMessage()} is logged server-side via {@code log.error(..., e)}
     * — the body never carries internal infrastructure details like Mongo
     * cluster topology, Spring bean wiring failures, or JDK {@code HttpClient}
     * hostnames.
     */
    private static final String INTERNAL_ERROR_MSG = "Internal server error — see server logs";

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
     * loud-restart signals.
     * <p>
     * The body's {@code msg} is intentionally a fixed string, not
     * {@code e.getMessage()} — an {@link IllegalStateException} from
     * {@code ApiGatewayClient.checkInitialized} or similar carries internal
     * class names that should not reach the client. The full exception is
     * logged server-side at ERROR so the operator can correlate via request
     * URI and stacktrace.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error", INTERNAL_ERROR_MSG));
    }

    /**
     * Override Spring's default {@link HttpMessageNotReadableException}
     * handling so the body shape matches our {@code {type, msg}} envelope.
     * The canned "Malformed request body" is intentional: the underlying
     * Jackson parser exception is verbose and may leak class internals.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                   HttpHeaders headers,
                                                                   HttpStatusCode status,
                                                                   WebRequest request) {
        log.warn("Handled {} from {}: {}", ex.getClass().getSimpleName(),
                describe(request), ex.getMessage());
        return handleExceptionInternal(ex,
                new ErrorResponse("Bad request", "Malformed request body"),
                headers, status, request);
    }

    /**
     * Terminal fallback. Logs the full stacktrace because, by definition, we
     * don't have a specific mapping for this type and the operator will need
     * the trace to diagnose.
     * <p>
     * The body's {@code msg} is a fixed string — {@code e.getMessage()} for
     * an unhandled exception can carry Mongo hostnames, Spring bean wiring
     * failures, JDK {@code HttpClient} infra details, etc. These belong only
     * in the server log, never in the response. Typed exception arms
     * ({@link UpstreamGatewayException}, {@link BadRequestException},
     * {@link IllegalArgumentException}) continue to forward their messages
     * verbatim — those messages are operator-safe by construction.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception e, HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal error", INTERNAL_ERROR_MSG));
    }

    /**
     * Default formatting hook used by every superclass arm
     * ({@code handleHttpRequestMethodNotSupported},
     * {@code handleHttpMediaTypeNotSupported},
     * {@code handleMissingServletRequestParameter}, etc.). Replaces Spring's
     * default {@code ProblemDetail} body with our {@code ErrorResponse} so
     * the API stays uniform across all error responses.
     * <p>
     * If a specific superclass arm already populated {@code body} (e.g. our
     * {@link #handleHttpMessageNotReadable} override above), it is forwarded
     * untouched. Otherwise we synthesise a {@link ErrorResponse} whose
     * {@code type} reflects the status code's intent (405 → "Method not
     * allowed", 415 → "Unsupported media type", etc.). The status code is
     * always whatever Spring's default mapping produced — we never override
     * it here.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                              Object body,
                                                              HttpHeaders headers,
                                                              HttpStatusCode statusCode,
                                                              WebRequest request) {
        log.warn("Handled {} from {}: {}", ex.getClass().getSimpleName(),
                describe(request), ex.getMessage());

        Object responseBody = body;
        if (responseBody == null || !(responseBody instanceof ErrorResponse)) {
            responseBody = new ErrorResponse(typeForStatus(statusCode), msgForStatus(statusCode, ex));
        }
        HttpHeaders responseHeaders = headers != null ? headers : new HttpHeaders();
        return super.handleExceptionInternal(ex, responseBody, responseHeaders, statusCode, request);
    }

    /**
     * Map a Spring-resolved status code to the {@code type} discriminator in
     * the response body. Bounded to a small set so the frontend can branch on
     * the value without parsing localised messages.
     */
    private static String typeForStatus(HttpStatusCode statusCode) {
        int value = statusCode.value();
        return switch (value) {
            case 400 -> "Bad request";
            case 405 -> "Method not allowed";
            case 406 -> "Not acceptable";
            case 415 -> "Unsupported media type";
            default -> value >= 500 ? "Internal error" : "Bad request";
        };
    }

    /**
     * Pick the {@code msg} for a Spring-resolved status. For 4xx we forward
     * {@code ex.getMessage()} — Spring's own servlet exceptions construct
     * operator-safe messages (e.g. "Request method 'GET' is not supported").
     * For 5xx we use the sanitised fixed string, same rationale as
     * {@link #handleAny}.
     */
    private static String msgForStatus(HttpStatusCode statusCode, Exception ex) {
        if (statusCode.value() >= 500) {
            return INTERNAL_ERROR_MSG;
        }
        String exMsg = ex.getMessage();
        return exMsg != null ? exMsg : statusCode.toString();
    }

    /**
     * Extract a request descriptor for log lines from the {@link WebRequest}
     * Spring hands us in the superclass hooks. Mirrors the URI-style format
     * used by the {@code HttpServletRequest}-based handlers in this class.
     */
    private static String describe(WebRequest request) {
        // WebRequest.getDescription(false) returns "uri=/foo/bar"; strip the
        // "uri=" prefix so the log line shape matches the HttpServletRequest
        // arms ("Handled X from /foo/bar: ...").
        String desc = request != null ? request.getDescription(false) : null;
        if (desc != null && desc.startsWith("uri=")) {
            return desc.substring(4);
        }
        return desc != null ? desc : "(unknown)";
    }
}
