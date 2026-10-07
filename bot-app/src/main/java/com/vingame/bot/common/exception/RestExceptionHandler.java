package com.vingame.bot.common.exception;

import com.vingame.bot.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
 *   <li>{@link GatewayBudgetExhaustedException} &rarr; 429 with {@code Retry-After};
 *       {@link GatewayCircuitOpenException} &rarr; 503 with {@code Retry-After}. Neither is a
 *       502, because 502 says the gateway failed and it did not — <b>this JVM declined to
 *       send</b> (GATEWAY_REQUEST_BUDGET AD-11)</li>
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
    /**
     * Sanitised body text for anything whose real message may not reach a client. Now held by
     * {@link ClientSafeMessage}, which is the same policy applied to the one place an error can
     * reach a client without being an HTTP status: {@code BotGroupStatusDTO.lastError} on an
     * asynchronous start (GATEWAY_REQUEST_BUDGET R2). One string, one rule.
     */
    private static final String INTERNAL_ERROR_MSG = ClientSafeMessage.INTERNAL_ERROR;

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

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException e, HttpServletRequest request) {
        log.info("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Conflict", e.getMessage()));
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
     * The per-environment request window had no room within the tier's wait: <b>429</b> with
     * {@code Retry-After} (GATEWAY_REQUEST_BUDGET AD-11).
     * <p>
     * Deliberately not a 502. A 502 says the upstream answered and the answer was unusable, and
     * would send an operator to look at a gateway that is working perfectly; this says the app
     * paced itself to stay under Cloudflare's 1,000-requests-per-5-minutes rule, and the remedy
     * is to wait or to stagger. {@code Retry-After} is the seconds until the earliest stamp in
     * the window expires — the soonest a retry could <em>possibly</em> be admitted. Advice, not
     * a promise: a higher tier may take the freed slot first.
     * <p>
     * WARN, not ERROR: this is the feature working. It becomes interesting when it is sustained,
     * which is what {@code GatewayBudgetSustainedQueue} watches.
     */
    @ExceptionHandler(GatewayBudgetExhaustedException.class)
    public ResponseEntity<ErrorResponse> handleBudgetExhausted(GatewayBudgetExhaustedException e,
                                                               HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return retryAfter(HttpStatus.TOO_MANY_REQUESTS, e.getRetryAfter())
                .body(new ErrorResponse(e.getType(), e.getMessage()));
    }

    /**
     * A Cloudflare edge block is in force for this environment's gateway: <b>503</b> with
     * {@code Retry-After} (AD-11, as amended by A16.3).
     * <p>
     * <b>{@code Retry-After} here means "when we will next ASK", not "when it will work".</b> An
     * HTTP client needs a number, so the header carries the probe interval, and the truth is in the
     * body's {@code msg} — which since review F7 actually says it: the block may require operator
     * action, may last a day or more, and the circuit closes only when a probe is answered. Until
     * F7 the body said "circuit open for another 3600s", i.e. it repeated the promise the header
     * was excused for making, in the one place that had room to qualify it.
     * <p>
     * ERROR, unlike its sibling: every bot on that brand is unable to log in, re-authenticate,
     * deposit or reconnect until a human acts.
     */
    @ExceptionHandler(GatewayCircuitOpenException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(GatewayCircuitOpenException e,
                                                           HttpServletRequest request) {
        log.error("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return retryAfter(HttpStatus.SERVICE_UNAVAILABLE, e.getRetryAfter())
                .body(new ErrorResponse(e.getType(), e.getMessage()));
    }

    /**
     * Terminal fallback for the rest of the budget hierarchy.
     * <p>
     * {@link GatewayRequestCancelledException} is the only other member and it is documented as
     * never reaching REST — it is thrown into a start something else already decided to abandon.
     * This arm exists so that "never" is a <b>429 naming the cancellation</b> rather than a
     * sanitised 500 with a class name, because the alternative to writing it down is finding out
     * from a support ticket. Also catches any future subclass, at the sibling of the status its
     * author would most likely have wanted.
     */
    @ExceptionHandler(GatewayBudgetException.class)
    public ResponseEntity<ErrorResponse> handleBudget(GatewayBudgetException e,
                                                      HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(e.getType(), e.getMessage()));
    }

    /**
     * {@code Retry-After} in seconds, omitted entirely when the duration is unknown.
     * <p>
     * Never zero and never negative: {@code Retry-After: 0} invites an immediate retry, which for
     * a client obeying the header is a tight loop against the exact condition that produced it.
     * One second is the floor.
     */
    private static ResponseEntity.BodyBuilder retryAfter(HttpStatus status, java.time.Duration after) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (after != null) {
            builder.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1L, after.toSeconds())));
        }
        return builder;
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
    /**
     * A request that needed the plugin registries after their bundle closed, i.e. during
     * JVM shutdown (PLUGIN_HOT_RELOAD_3_4, review-4a) — e.g. {@code GET /api/v1/strategy/}
     * or a strategy-key PATCH racing the stop. The service is going away, not broken, so
     * 503 rather than the 500 its {@link IllegalStateException} supertype would map to, and
     * WARN without a stack trace. Must stay declared alongside the {@code IllegalStateException}
     * arm: Spring picks the most specific handler, so this one wins for the subtype.
     */
    @ExceptionHandler(com.vingame.bot.infrastructure.plugin.PluginUnpublishedException.class)
    public ResponseEntity<ErrorResponse> handlePluginUnpublished(
            com.vingame.bot.infrastructure.plugin.PluginUnpublishedException e, HttpServletRequest request) {
        log.warn("Handled {} from {}: {}", e.getClass().getSimpleName(),
                request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("Service unavailable", e.getMessage()));
    }

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
     * Override Spring's default {@link MethodArgumentNotValidException} handling
     * (thrown by {@code @Valid}/{@code @Validated} request-body failures) so the
     * body matches our {@code {type, msg}} envelope and the message aggregates
     * every field violation — mirroring the service-layer game-type validators,
     * which join all violations with {@code "; "} into one
     * {@link BadRequestException}.
     * <p>
     * Field-error default messages are operator-safe by construction here: they
     * are the explicit {@code message =} text on the DTO constraints
     * (e.g. {@code "gameId must not be blank"}), not raw exception internals.
     * Errors are sorted by field name for a deterministic, test-stable order.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .sorted(java.util.Comparator.comparing(FieldError::getField))
                .map(FieldError::getDefaultMessage)
                .collect(java.util.stream.Collectors.joining("; "));
        if (msg.isBlank()) {
            msg = "Validation failed";
        }
        log.warn("Handled {} from {}: {}", ex.getClass().getSimpleName(),
                describe(request), msg);
        return handleExceptionInternal(ex,
                new ErrorResponse("Bad request", msg),
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
