package com.vingame.bot.domain.bot.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSON wire shape produced by the three brand-specific login requests.
 * <p>
 * These shapes are the source of {@code TIP_LOGIN_JSON_SHAPE_DEPLOY} and
 * {@code TIP_GWMS_PATHS_DEPLOY} incidents in 2026 — a {@code @JsonProperty}
 * drop or field rename today fails no test today and just silently breaks
 * production auth for one product. Each test reads the result as a
 * {@link JsonNode} (the same view the upstream gateway has), not via Java
 * getter inspection, so an accidentally-camelCased field would be caught.
 */
@DisplayName("LoginRequest JSON serialization shapes")
class LoginRequestSerializationTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("TipLoginRequest (P_116)")
    class TipShape {

        @Test
        @DisplayName("brand-static fields match the documented Tip wire shape")
        void tipLoginRequest_serializesWithExpectedStaticFields() {
            TipLoginRequest req = new TipLoginRequest("alice", "hunter2", "fp-123", "10.0.0.1");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("app_id").asText()).isEqualTo("bc115116");
            assertThat(json.get("apVer").asText()).isEqualTo("0.0.912");
            assertThat(json.get("version").asText()).isEqualTo("0.0.912");
            assertThat(json.get("aff_id").asText()).isEqualTo("");
            assertThat(json.get("os").asText()).isEqualTo("OS X");
            assertThat(json.get("device").asText()).isEqualTo("Computer");
            assertThat(json.get("browser").asText()).isEqualTo("chrome");
        }

        @Test
        @DisplayName("constructor-supplied fields propagate to JSON unchanged")
        void tipLoginRequest_propagatesConstructorFields() {
            TipLoginRequest req = new TipLoginRequest("alice", "hunter2", "fp-123", "10.0.0.1");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("username").asText()).isEqualTo("alice");
            assertThat(json.get("password").asText()).isEqualTo("hunter2");
            assertThat(json.get("fg").asText()).isEqualTo("fp-123");
            assertThat(json.get("ip").asText()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("does not emit camelCase variants of snake_case fields")
        void tipLoginRequest_doesNotEmitCamelCaseVariants() {
            TipLoginRequest req = new TipLoginRequest("alice", "hunter2", "fp-123", "10.0.0.1");
            JsonNode json = mapper.valueToTree(req);

            // Catches a future drop of @JsonProperty("app_id") / @JsonProperty("aff_id")
            // / @JsonProperty("fg") that would silently flip the wire shape.
            assertThat(json.has("appId")).isFalse();
            assertThat(json.has("affId")).isFalse();
            assertThat(json.has("fingerprint")).isFalse();
        }
    }

    @Nested
    @DisplayName("BomLoginRequest (P_097)")
    class BomShape {

        @Test
        @DisplayName("brand-static fields match the documented Bom wire shape")
        void bomLoginRequest_serializesWithExpectedStaticFields() {
            BomLoginRequest req = new BomLoginRequest("bob", "secret", "fp-456", "10.0.0.2");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("app_id").asText()).isEqualTo("bc114097");
            assertThat(json.get("apVer").asText()).isEqualTo("0.0.636");
            assertThat(json.get("version").asText()).isEqualTo("0.0.636");
            assertThat(json.get("aff_id").asText()).isEqualTo("");
            assertThat(json.get("os").asText()).isEqualTo("OS X");
            assertThat(json.get("device").asText()).isEqualTo("Computer");
            assertThat(json.get("browser").asText()).isEqualTo("chrome");
        }

        @Test
        @DisplayName("constructor-supplied fields propagate to JSON unchanged")
        void bomLoginRequest_propagatesConstructorFields() {
            BomLoginRequest req = new BomLoginRequest("bob", "secret", "fp-456", "10.0.0.2");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("username").asText()).isEqualTo("bob");
            assertThat(json.get("password").asText()).isEqualTo("secret");
            assertThat(json.get("fg").asText()).isEqualTo("fp-456");
            assertThat(json.get("ip").asText()).isEqualTo("10.0.0.2");
        }

        @Test
        @DisplayName("does not emit camelCase variants of snake_case fields")
        void bomLoginRequest_doesNotEmitCamelCaseVariants() {
            BomLoginRequest req = new BomLoginRequest("bob", "secret", "fp-456", "10.0.0.2");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.has("appId")).isFalse();
            assertThat(json.has("affId")).isFalse();
            assertThat(json.has("fingerprint")).isFalse();
        }
    }

    @Nested
    @DisplayName("B52LoginRequest (P_098)")
    class B52Shape {

        @Test
        @DisplayName("brand-static fields match the documented B52 wire shape")
        void b52LoginRequest_serializesWithExpectedStaticFields() {
            B52LoginRequest req = new B52LoginRequest("carol", "pa55", "fp-789", "10.0.0.3");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("app_id").asText()).isEqualTo("bc114098");
            assertThat(json.get("apVer").asText()).isEqualTo("0.0.1197");
            assertThat(json.get("version").asText()).isEqualTo("0.0.1197");
            assertThat(json.get("aff_id").asText()).isEqualTo("");
            assertThat(json.get("os").asText()).isEqualTo("OS X");
            assertThat(json.get("device").asText()).isEqualTo("Computer");
            assertThat(json.get("browser").asText()).isEqualTo("chrome");
        }

        @Test
        @DisplayName("constructor-supplied fields propagate to JSON unchanged")
        void b52LoginRequest_propagatesConstructorFields() {
            B52LoginRequest req = new B52LoginRequest("carol", "pa55", "fp-789", "10.0.0.3");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.get("username").asText()).isEqualTo("carol");
            assertThat(json.get("password").asText()).isEqualTo("pa55");
            assertThat(json.get("fg").asText()).isEqualTo("fp-789");
            assertThat(json.get("ip").asText()).isEqualTo("10.0.0.3");
        }

        @Test
        @DisplayName("does not emit camelCase variants of snake_case fields")
        void b52LoginRequest_doesNotEmitCamelCaseVariants() {
            B52LoginRequest req = new B52LoginRequest("carol", "pa55", "fp-789", "10.0.0.3");
            JsonNode json = mapper.valueToTree(req);

            assertThat(json.has("appId")).isFalse();
            assertThat(json.has("affId")).isFalse();
            assertThat(json.has("fingerprint")).isFalse();
        }
    }
}
