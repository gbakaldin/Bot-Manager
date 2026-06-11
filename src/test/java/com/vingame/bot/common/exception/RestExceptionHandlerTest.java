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
    @DisplayName("IllegalStateException -> 500 with {type:'Internal error', msg} (transitional)")
    void illegalState_returns500WithBody() throws Exception {
        mockMvc.perform(get("/__test/illegal-state"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value("not initialized"));
    }

    @Test
    @DisplayName("Generic Exception -> 500 with {type:'Internal error', msg}")
    void anyException_returns500WithBody() throws Exception {
        mockMvc.perform(get("/__test/generic"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value("boom"));
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
    @DisplayName("Terminal Exception fallback uses class simple name when getMessage() is null")
    void nullMessageException_returns500WithClassSimpleName() throws Exception {
        // Cover the AD-6 null-message branch in handleAny — without this arm,
        // the JSON body would carry "msg":null and operators would lose all
        // signal on what blew up.
        mockMvc.perform(get("/__test/null-message"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("Internal error"))
                .andExpect(jsonPath("$.msg").value("RuntimeException"));
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

        @PostMapping("/__test/echo")
        public String echo(@RequestBody java.util.Map<String, Object> body) {
            return body.toString();
        }

        @GetMapping("/__test/upstream-custom-type")
        public String upstreamCustomType() {
            // Anonymous subclass of the abstract base lets us verify
            // getType() propagation without minting a real production type
            // just for this test.
            throw new UpstreamGatewayException("Custom upstream type",
                    "custom upstream failure") {};
        }
    }
}
