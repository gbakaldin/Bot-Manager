package com.vingame.bot.infrastructure.auth;

import com.vingame.bot.domain.bot.auth.B52LoginRequest;
import com.vingame.bot.domain.bot.auth.BomLoginRequest;
import com.vingame.bot.domain.bot.auth.TipLoginRequest;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.websocketparser.auth.AuthContext;
import com.vingame.websocketparser.auth.DefaultLoginRequest;
import com.vingame.websocketparser.auth.LoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuthStrategyFactory}.
 * <p>
 * Two arms are covered per product code:
 * <ol>
 *   <li>The static path/header fields on the returned {@link AuthProfile} — every
 *       product currently returns the same gwms paths and X-TOKEN. A divergence
 *       between products is suspicious and must be opt-in.</li>
 *   <li>The dynamic type of the object produced by {@code loginRequestFactory} —
 *       this is the wire shape sent to the upstream auth gateway. A future enum
 *       constant added without a switch arm fails compile today, but a future
 *       refactor to an {@code if/else} ladder or {@code default ->} branch could
 *       silently route a new product to the wrong factory.</li>
 * </ol>
 */
@DisplayName("AuthStrategyFactory")
class AuthStrategyFactoryTest {

    private static final String LOGIN_PATH = "/gwms/v1/bot/login.aspx";
    private static final String REGISTRATION_PATH = "/gwms/v1/bot/register.aspx";
    private static final String UPDATE_FULLNAME_PATH = "/gwms/v1/bot/update-fullname.aspx";
    private static final String X_TOKEN = "58bc2820612d23c34fe43d0b2c6f7223";
    private static final String BOT_IP = "1.2.3.4";

    private AuthStrategyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new AuthStrategyFactory();
        // @Value("${bot.ip}") is not Spring-resolved in this unit test — set it
        // manually so the Tip/Bom/B52 closures embed a real IP instead of null.
        ReflectionTestUtils.setField(factory, "botIp", BOT_IP);
    }

    private Environment envFor(ProductCode pc) {
        return Environment.builder()
                .name("env-for-" + pc.getCode())
                .productCode(pc)
                .build();
    }

    private AuthContext fakeAuthContext() {
        return new AuthContext(
                "https://api.example.test",
                "alice",
                "hunter2",
                "bc114097",
                "fp-abc",
                LOGIN_PATH,
                X_TOKEN
        );
    }

    @Test
    @DisplayName("throws IllegalArgumentException when env has no productCode")
    void getAuthProfile_throwsWhenProductCodeIsNull() {
        Environment env = Environment.builder().name("misconfigured-env").productCode(null).build();

        assertThatThrownBy(() -> factory.getAuthProfile(env))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("misconfigured-env");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProductCode.class)
    @DisplayName("paths and X-TOKEN are identical across every product code")
    void getAuthProfile_pathsAndXTokenSameAcrossAllProductCodes(ProductCode pc) {
        AuthProfile profile = factory.getAuthProfile(envFor(pc));

        assertThat(profile.loginPath()).isEqualTo(LOGIN_PATH);
        assertThat(profile.registrationPath()).isEqualTo(REGISTRATION_PATH);
        assertThat(profile.updateFullnamePath()).isEqualTo(UPDATE_FULLNAME_PATH);
        assertThat(profile.xToken()).isEqualTo(X_TOKEN);
    }

    @ParameterizedTest(name = "{0} → DefaultLoginRequest")
    @EnumSource(value = ProductCode.class, names = {"P_066", "P_103", "P_105", "P_114", "P_118", "P_119", "P_222"})
    @DisplayName("standard products produce a DefaultLoginRequest")
    void getAuthProfile_loginRequestFactoryReturnsDefaultLoginRequest_forStandardProducts(ProductCode pc) {
        AuthProfile profile = factory.getAuthProfile(envFor(pc));

        LoginRequest loginRequest = profile.loginRequestFactory().apply(fakeAuthContext());

        assertThat(loginRequest).isInstanceOf(DefaultLoginRequest.class);
        DefaultLoginRequest dlr = (DefaultLoginRequest) loginRequest;
        assertThat(dlr.getUsername()).isEqualTo("alice");
        assertThat(dlr.getPassword()).isEqualTo("hunter2");
        assertThat(dlr.getApp_id()).isEqualTo("bc114097");
        assertThat(dlr.getFg()).isEqualTo("fp-abc");
    }

    @Test
    @DisplayName("P_116 (Tip) produces a TipLoginRequest with botIp")
    void getAuthProfile_loginRequestFactoryReturnsTipLoginRequest_forP116() {
        AuthProfile profile = factory.getAuthProfile(envFor(ProductCode.P_116));

        LoginRequest loginRequest = profile.loginRequestFactory().apply(fakeAuthContext());

        assertThat(loginRequest).isInstanceOf(TipLoginRequest.class);
        TipLoginRequest tip = (TipLoginRequest) loginRequest;
        assertThat(tip.getUsername()).isEqualTo("alice");
        assertThat(tip.getPassword()).isEqualTo("hunter2");
        assertThat(tip.getFingerprint()).isEqualTo("fp-abc");
        assertThat(tip.getIp()).isEqualTo(BOT_IP);
        // app_id on Tip is brand-static, not driven by ctx.appId() — pin that.
        assertThat(tip.getAppId()).isEqualTo("bc115116");
    }

    @Test
    @DisplayName("P_097 (Bom) produces a BomLoginRequest with botIp")
    void getAuthProfile_loginRequestFactoryReturnsBomLoginRequest_forP097() {
        AuthProfile profile = factory.getAuthProfile(envFor(ProductCode.P_097));

        LoginRequest loginRequest = profile.loginRequestFactory().apply(fakeAuthContext());

        assertThat(loginRequest).isInstanceOf(BomLoginRequest.class);
        BomLoginRequest bom = (BomLoginRequest) loginRequest;
        assertThat(bom.getUsername()).isEqualTo("alice");
        assertThat(bom.getPassword()).isEqualTo("hunter2");
        assertThat(bom.getFingerprint()).isEqualTo("fp-abc");
        assertThat(bom.getIp()).isEqualTo(BOT_IP);
        assertThat(bom.getAppId()).isEqualTo("bc114097");
    }

    @Test
    @DisplayName("P_098 (B52) produces a B52LoginRequest with botIp")
    void getAuthProfile_loginRequestFactoryReturnsB52LoginRequest_forP098() {
        AuthProfile profile = factory.getAuthProfile(envFor(ProductCode.P_098));

        LoginRequest loginRequest = profile.loginRequestFactory().apply(fakeAuthContext());

        assertThat(loginRequest).isInstanceOf(B52LoginRequest.class);
        B52LoginRequest b52 = (B52LoginRequest) loginRequest;
        assertThat(b52.getUsername()).isEqualTo("alice");
        assertThat(b52.getPassword()).isEqualTo("hunter2");
        assertThat(b52.getFingerprint()).isEqualTo("fp-abc");
        assertThat(b52.getIp()).isEqualTo(BOT_IP);
        assertThat(b52.getAppId()).isEqualTo("bc114098");
    }

    @Test
    @DisplayName("each standard product produces a fresh DefaultLoginRequest instance per call")
    void getAuthProfile_factoryProducesFreshInstanceOnEachCall() {
        AuthProfile profile = factory.getAuthProfile(envFor(ProductCode.P_103));
        LoginRequest a = profile.loginRequestFactory().apply(fakeAuthContext());
        LoginRequest b = profile.loginRequestFactory().apply(fakeAuthContext());

        // Each call must yield a new instance — the factory must not cache and
        // accidentally share mutable state across bots.
        assertThat(a).isNotSameAs(b);
    }
}
