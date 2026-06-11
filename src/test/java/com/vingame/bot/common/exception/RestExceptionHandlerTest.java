package com.vingame.bot.common.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    }
}
