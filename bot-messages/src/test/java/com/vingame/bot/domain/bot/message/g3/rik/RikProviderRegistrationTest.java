package com.vingame.bot.domain.bot.message.g3.rik;

import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.message.taixiu.JackpotTaiXiuMessageTypes;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code "114"} resolves <b>this</b> provider, under a real component scan.
 * <p>
 * {@code MessageTypesCoverageTest} proves only that 114 resolves <i>something</i> and
 * {@code MessageTypesRegistryTest} only that the key is in the registered set —
 * neither pins the class, and a mis-annotated provider (wrong product string, wrong
 * {@code gameType}, a copy-pasted {@code @MessageTypesImpl}) would satisfy both.
 * The failure that costs is the one that surfaces on a bot thread at group start,
 * after authentication, on the box.
 *
 * <h2>The 114 Tài/Xỉu trap</h2>
 *
 * The brand runs a Tài/Xỉu-themed game under <b>both</b> game types and they share
 * nothing. {@code taixiuMd5Plugin} is a {@code BETTING_MINI} game (CODE + offset:
 * {@code 7000}/{@code 7005}/{@code 7006} at offset 4000) served by this provider,
 * while {@code taixiuJackpotPlugin} is the fixed-CMD {@code TAI_XIU} product
 * ({@code 1105}/{@code 1102}/{@code 1104}/{@code 1100}) served by
 * {@link JackpotTaiXiuMessageTypes}. Creating the first with
 * {@code gameType: TAI_XIU} subscribes on a CMD the game never answers and the group
 * simply never sees a round. Both sides of that split are asserted below so neither
 * table can quietly swallow the other's key.
 */
@DisplayName("RikGameMessageTypes - registration under the real component scan")
class RikProviderRegistrationTest {

    private static final String SCAN_BASE = "com.vingame.bot.domain.bot.message";

    private static AnnotationConfigApplicationContext context;
    private static MessageTypesRegistry registry;

    @BeforeAll
    static void bootRealContext() {
        context = new AnnotationConfigApplicationContext();
        context.scan(SCAN_BASE);
        context.refresh();
        registry = context.getBean(MessageTypesRegistry.class);
    }

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("bettingMini(\"114\") resolves RikGameMessageTypes — the resolution that used to throw")
    void bettingMiniResolvesRik() {
        assertThat(registry.bettingMini(ProductCode.P_114.getCode()))
                .isInstanceOf(RikGameMessageTypes.class);
    }

    @Test
    @DisplayName("taiXiu(\"114\") is untouched — the two game types key the same product into different tables")
    void taiXiuForTheSameProductIsUnchanged() {
        assertThat(registry.taiXiu(ProductCode.P_114.getCode()))
                .isInstanceOf(JackpotTaiXiuMessageTypes.class);
    }

    @Test
    @DisplayName("every accessor is populated — no null slot to NPE at registration (cf. Win79's null md5 type)")
    void everyAccessorIsPopulated() {
        GameMessageTypes types = registry.bettingMini(ProductCode.P_114.getCode());

        assertThat(types.subscribeType()).isEqualTo(RikSubscribeMessage.class);
        assertThat(types.startGameType()).isEqualTo(RikStartGameMessage.class);
        assertThat(types.updateBetType()).isEqualTo(RikUpdateBetMessage.class);
        assertThat(types.endGameType()).isEqualTo(RikEndGameMessage.class);
        // AD-12: unlike Win79GameMessageTypes this is NOT null, and it is not a hedge
        // — taixiuMd5Plugin carries a real 64-hex hash, so Game.md5 = true is a
        // captured configuration. A null here would NPE at registerSubtypes().
        assertThat(types.startGameMd5Type()).isEqualTo(RikStartGameMd5Message.class);
    }

    @Test
    @DisplayName("CMDs come from the Game's offset, never hardcoded (AD-2) — same provider, two live games")
    void cmdsAreDerivedFromTheOffset() {
        GameMessageTypes types = registry.bettingMini(ProductCode.P_114.getCode());

        // stockPlugin, offset 10000, md5 = false.
        assertThat(namesOf(types.getTypeRegistrations(10000, false)))
                .containsExactlyInAnyOrder("13000", "13002", "13005", "13006");
        assertThat(typeFor(types.getTypeRegistrations(10000, false), "13005"))
                .isEqualTo(RikStartGameMessage.class);

        // taixiuMd5Plugin, offset 4000, md5 = true — the md5 flag swaps exactly one
        // registration and nothing else.
        assertThat(namesOf(types.getTypeRegistrations(4000, true)))
                .containsExactlyInAnyOrder("7000", "7002", "7005", "7006");
        assertThat(typeFor(types.getTypeRegistrations(4000, true), "7005"))
                .isEqualTo(RikStartGameMd5Message.class);
        assertThat(typeFor(types.getTypeRegistrations(4000, true), "7006"))
                .isEqualTo(RikEndGameMessage.class);

        // An un-captured 114 game at another offset binds with no code change — which
        // is what makes AD-11's product-wide claim survivable (OI-2 still requires a
        // capture before one is enabled). Offset 14000 is one of the live-but-
        // uncaptured 114 games §4b saw in the capture's outsideWindow, as CMD 17000.
        assertThat(namesOf(types.getTypeRegistrations(14000, false)))
                .containsExactlyInAnyOrder("17000", "17002", "17005", "17006");
    }

    private List<String> namesOf(NamedType[] registrations) {
        return Arrays.stream(registrations).map(NamedType::getName).toList();
    }

    private Class<?> typeFor(NamedType[] registrations, String name) {
        return Arrays.stream(registrations)
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no registration named " + name))
                .getType();
    }
}
