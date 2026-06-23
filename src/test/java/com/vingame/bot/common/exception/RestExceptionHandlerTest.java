package com.vingame.bot.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies {@link RestExceptionHandler} maps each known exception type to its
 * intended HTTP status + body shape. Uses a tiny {@code @RestController} stub
 * that throws on demand so we exercise the advice through the real MVC
 * machinery (filters, content negotiation, message converters) — not just a
 * unit-level invocation of the handler methods.
 */
@WebMvcTest(RestExceptionHandlerTest.TestController.class)
@Import({RestExceptionHandler.class, RestExceptionHandlerTest.TestController.class})
@DisplayName("RestExceptionHandler")
class RestExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("ResourceNotFoundException -> 404 with empty body")
    void notFound_returns404BodyLess() throws Exception {
        mockMvc.perform(get("/__test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("BadRequestException -> 400 with {type:'Bad request', msg}")
    void badRequest_returns400WithBody() throws Exception {
        mockMvc.perform(get("/__test/bad-request"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value("invalid value"));
    }

    @Test
    @DisplayName("IllegalArgumentException -> 400 with {type:'Bad request', msg}")
    void illegalArgument_returns400WithBody() throws Exception {
        mockMvc.perform(get("/__test/illegal-argument"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value("bad arg"));
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException -> 400 with parameter detail")
    void typeMismatch_returns400WithParameterMessage() throws Exception {
        // Trigger a real type-mismatch by passing a non-integer to an int path
        // variable. Spring raises MethodArgumentTypeMismatchException internally.
        mockMvc.perform(get("/__test/int-only/{value}", "not-an-int"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("value")))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("not-an-int")));
    }

    @Test
    @DisplayName("UpstreamRegistrationException -> 502 with {type:'Game server error', msg}")
    void upstreamRegistration_returns502WithGameServerErrorType() throws Exception {
        mockMvc.perform(get("/__test/upstream-registration"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("Game server error"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                        "Tên đăng nhập không được nhiều hơn 12 ký tự")));
    }

    @Test
    @DisplayName("UpstreamLoginException -> 502 with {type:'Game server error', msg}")
    void upstreamLogin_returns502WithGameServerErrorType() throws Exception {
        mockMvc.perform(get("/__test/upstream-login"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("Game server error"))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString(
                        "Login failed for user")));
    }

    @Test
    @DisplayName("IllegalStateException -> 500 with sanitised body (msg does not echo e.getMessage())")
    void illegalState_returns500WithSanitisedBody() throws Exception {
        // Security: raw IllegalStateException messages from initialization
        // checks ("ApiGatewayClient not initialized...") carry internal class
        // state and must not reach the client. The full exception is logged
        // server-side via log.error(..., e); operators correlate via the
        // request URI in the log line.
        mockMvc.perform(get("/__test/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("not initialized"))))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("Internal server error")));
    }

    @Test
    @DisplayName("Generic Exception -> 500 with sanitised body (msg does not echo e.getMessage())")
    void anyException_returns500WithSanitisedBody() throws Exception {
        // Same rationale as illegalState_returns500WithSanitisedBody: the
        // generic 500 fallback regularly catches Mongo connection failures,
        // Spring bean wiring failures, JDK HttpClient infra errors — all of
        // which carry hostnames, ports, class names. The body is sanitised;
        // the server log carries the trace.
        mockMvc.perform(get("/__test/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("boom"))))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("Internal server error")));
    }

    @Test
    @DisplayName("Response carries application/json content type for non-2xx with body")
    void errorResponseIsJson() throws Exception {
        mockMvc.perform(get("/__test/bad-request"))
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/json")));
    }

    @Test
    @DisplayName("HttpMessageNotReadableException (malformed JSON body) -> 400 with 'Malformed request body' msg")
    void malformedBody_returns400WithCannedMessage() throws Exception {
        // Force the message converter to fail by posting invalid JSON to an
        // endpoint that requires @RequestBody. Spring surfaces this as
        // HttpMessageNotReadableException — the handler's canned response is
        // intentional: the underlying parser exception is verbose and may leak
        // class internals; the operator only needs to know the body was bad.
        mockMvc.perform(post("/__test/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value("Malformed request body"));
    }

    @Test
    @DisplayName("Terminal Exception fallback always emits the sanitised msg, even when e.getMessage() is null")
    void nullMessageException_returns500WithSanitisedBody() throws Exception {
        // The fixed sanitised message is unconditional — both null and
        // non-null exception messages take the same body path. Operators
        // still get the class name in the server log via log.error(..., e).
        mockMvc.perform(get("/__test/null-message"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("Internal server error")));
    }

    @Test
    @DisplayName("ResourceNotFoundException -> 404 has no Content-Type/body header (bodyless contract)")
    void notFound_hasNoBody_strict() throws Exception {
        // Stronger than the basic 404 assertion: confirm the response body is
        // not just empty-string but actually has zero length. UI code today
        // branches on .status === 404 without reading the body; making the
        // 404 body shape part of the contract prevents accidental drift to
        // "{type, msg}" in a future refactor.
        mockMvc.perform(get("/__test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().bytes(new byte[0]));
    }

    @Test
    @DisplayName("Sanitised 500: Mongo-style infra hostname in e.getMessage() never reaches the response body")
    void anyException_doesNotLeakInfraHostnameInBody() throws Exception {
        // Concrete security regression: a Mongo connection failure exposing
        // the internal cluster hostname must not appear in the response body.
        // The same shape covers Spring BeanCreationException, JDK
        // UnknownHostException, etc. — anything that bubbles to handleAny.
        mockMvc.perform(get("/__test/leaky-mongo"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mongo.internal"))))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("Internal server error")));
    }

    @Test
    @DisplayName("Sanitised 500: IllegalStateException carrying an infra hostname does not leak it in the body")
    void illegalState_doesNotLeakInfraHostnameInBody() throws Exception {
        // Mirrors handleAny coverage for the dedicated IllegalStateException
        // arm — e.g. ApiGatewayClient.checkInitialized today throws an ISE
        // whose message carries class state. Same sanitisation contract.
        mockMvc.perform(get("/__test/leaky-illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mongo.internal"))))
                .andExpect(jsonPath("$.msg").value(
                        org.hamcrest.Matchers.containsString("Internal server error")));
    }

    /* ---- Spring's default 4xx mappings preserved (advice extends ResponseEntityExceptionHandler) ---- */

    @Test
    @DisplayName("Wrong HTTP method -> 405 with {type:'Method not allowed', msg}")
    void wrongMethod_returns405WithBody() throws Exception {
        // /__test/echo is mapped to POST only; a GET must surface as 405,
        // not 500 (which is what would happen if the terminal Exception arm
        // swallowed Spring's HttpRequestMethodNotSupportedException).
        mockMvc.perform(get("/__test/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("Method not allowed"));
    }

    @Test
    @DisplayName("Unsupported content type -> 415 with {type:'Unsupported media type', msg}")
    void unsupportedMediaType_returns415WithBody() throws Exception {
        // /__test/echo consumes application/json; a text/plain body must
        // produce 415, not 500.
        mockMvc.perform(post("/__test/echo")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.type").value("Unsupported media type"));
    }

    @Test
    @DisplayName("Missing required @RequestParam -> 400 with {type:'Bad request', msg}")
    void missingRequiredQueryParam_returns400WithBody() throws Exception {
        // /__test/needs-param requires @RequestParam(name="q", required=true).
        // Spring raises MissingServletRequestParameterException; the advice
        // must keep this at 400, not promote it to 500.
        mockMvc.perform(get("/__test/needs-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"));
    }

    @Test
    @DisplayName("Abstract UpstreamGatewayException subclass uses the subclass-defined type verbatim")
    void upstreamGateway_typePropagatesFromSubclass() throws Exception {
        // The handler binds to the abstract base; the response 'type' must
        // come from the leaf via getType() (not hardcoded). This test exists
        // so a future leaf that overrides the type discriminator (e.g. a
        // hypothetical UpstreamBalanceException(type="Balance error", ...))
        // is exercised end-to-end through the same advice handler.
        mockMvc.perform(get("/__test/upstream-custom-type"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("Custom upstream type"))
                .andExpect(jsonPath("$.msg").value("custom upstream failure"));
    }

    @Test
    @DisplayName("MethodArgumentNotValid -> 400 with field errors joined, deterministically sorted by field name")
    void methodArgumentNotValidSortedAndAggregated() throws Exception {
        // All three @NotBlank fields blank. Declared order is zebra, alpha, mango;
        // the handler sorts by field name, so the msg must read alpha; mango; zebra.
        mockMvc.perform(post("/__test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zebra\":\"\",\"alpha\":\"\",\"mango\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value(
                        "alpha must not be blank; mango must not be blank; zebra must not be blank"));
    }

    @Test
    @DisplayName("MethodArgumentNotValid with a single field error -> 400 with just that message (no stray separators)")
    void methodArgumentNotValidSingleField() throws Exception {
        // Only mango blank; the other two satisfied — the msg must be exactly the
        // one field message with no trailing/leading "; ".
        mockMvc.perform(post("/__test/validated")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zebra\":\"z\",\"alpha\":\"a\",\"mango\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("Bad request"))
                .andExpect(jsonPath("$.msg").value("mango must not be blank"));
    }

    /**
     * Tiny test controller that throws each handled exception type on demand.
     * Defined static-inner so it stays scoped to this test class.
     */
    @RestController
    static class TestController {

        @GetMapping("/__test/not-found")
        public String notFound() {
            throw new ResourceNotFoundException("nothing here");
        }

        @GetMapping("/__test/bad-request")
        public String badRequest() {
            throw new BadRequestException("invalid value");
        }

        @GetMapping("/__test/illegal-argument")
        public String illegalArgument() {
            throw new IllegalArgumentException("bad arg");
        }

        @GetMapping("/__test/int-only/{value}")
        public String intOnly(@org.springframework.web.bind.annotation.PathVariable int value) {
            return "got " + value;
        }

        @GetMapping("/__test/upstream-registration")
        public String upstreamRegistration() {
            throw new UpstreamRegistrationException(
                    "Failed to register any users for bot group 'Demo'. " +
                            "Errors: Tên đăng nhập không được nhiều hơn 12 ký tự");
        }

        @GetMapping("/__test/upstream-login")
        public String upstreamLogin() {
            throw new UpstreamLoginException(
                    "Login failed for user 'authtest1': No data in response");
        }

        @GetMapping("/__test/illegal-state")
        public String illegalState() {
            throw new IllegalStateException("not initialized");
        }

        @GetMapping("/__test/generic")
        public String generic() {
            throw new RuntimeException("boom");
        }

        @GetMapping("/__test/null-message")
        public String nullMessage() {
            // RuntimeException with no message — the terminal Exception handler
            // must fall back to the class simple name (AD-6 / advice line ~121).
            throw new RuntimeException();
        }

        @PostMapping(value = "/__test/echo", consumes = MediaType.APPLICATION_JSON_VALUE)
        public String echo(@RequestBody java.util.Map<String, Object> body) {
            return body.toString();
        }

        @PostMapping(value = "/__test/validated", consumes = MediaType.APPLICATION_JSON_VALUE)
        public String validated(
                @org.springframework.web.bind.annotation.RequestBody
                @jakarta.validation.Valid ValidatedBody body) {
            return "ok";
        }

        @GetMapping("/__test/upstream-custom-type")
        public String upstreamCustomType() {
            // Anonymous subclass of the abstract base lets us verify
            // getType() propagation without minting a real production type
            // just for this test.
            throw new UpstreamGatewayException("Custom upstream type",
                    "custom upstream failure") {};
        }

        @GetMapping("/__test/leaky-mongo")
        public String leakyMongo() {
            // Models a Mongo connection failure whose message carries the
            // internal cluster hostname. The sanitised handler must not let
            // this reach the response body.
            throw new RuntimeException(
                    "Timed out after 30000 ms while waiting for a server that matches " +
                            "WritableServerSelector. Client view of cluster state is " +
                            "{type=UNKNOWN, servers=[{address=mongo.internal:27017, ...}]}");
        }

        @GetMapping("/__test/leaky-illegal-state")
        public String leakyIllegalState() {
            // Same shape as leakyMongo but routed through the dedicated
            // IllegalStateException arm — covers the
            // ApiGatewayClient.checkInitialized leak path.
            throw new IllegalStateException(
                    "ApiGatewayClient not initialized for cluster " +
                            "mongo.internal:27017");
        }

        @GetMapping("/__test/needs-param")
        public String needsParam(@org.springframework.web.bind.annotation.RequestParam(
                name = "q", required = true) String q) {
            // Forces MissingServletRequestParameterException when called
            // without ?q=... — Spring's default 400 mapping should survive
            // the advice's reformatting.
            return q;
        }
    }

    /**
     * Body with three {@code @NotBlank} fields whose declaration order
     * (zebra, alpha, mango) deliberately differs from alphabetical order, so a
     * passing ordering assertion proves the handler sorts by field name rather
     * than echoing declaration / reflection order.
     */
    static class ValidatedBody {
        @jakarta.validation.constraints.NotBlank(message = "zebra must not be blank")
        public String zebra;
        @jakarta.validation.constraints.NotBlank(message = "alpha must not be blank")
        public String alpha;
        @jakarta.validation.constraints.NotBlank(message = "mango must not be blank")
        public String mango;
    }
}
