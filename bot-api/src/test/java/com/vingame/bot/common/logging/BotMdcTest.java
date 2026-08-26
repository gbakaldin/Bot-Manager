package com.vingame.bot.common.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@code product} MDC key added by VIPTALK_ALERTING_V2 Phase 1 (AD-V1):
 * it is set when non-null, skipped when null (so an older {@code Game} document with
 * no {@code productCode} simply carries no label rather than an empty one), and
 * removed by {@link BotMdc#clear()} — the last of which is what stops one bot's
 * product leaking onto the next task to run on a pooled/virtual thread.
 */
class BotMdcTest {

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void set_putsProduct_whenPresent() {
        BotMdc.set("g-1", 3, "env-1", "116", "BETTING_MINI", "game-1", "BauCua", "bot3");

        assertThat(MDC.get(BotMdc.PRODUCT)).isEqualTo("116");
        assertThat(MDC.get(BotMdc.BOT_GROUP_ID)).isEqualTo("g-1");
        assertThat(MDC.get(BotMdc.ENVIRONMENT_ID)).isEqualTo("env-1");
        assertThat(MDC.get(BotMdc.GAME_TYPE)).isEqualTo("BETTING_MINI");
    }

    @Test
    void set_skipsProduct_whenNull() {
        BotMdc.set("g-1", 3, "env-1", null, "BETTING_MINI", "game-1", "BauCua", "bot3");

        assertThat(MDC.get(BotMdc.PRODUCT)).isNull();
        // everything else is unaffected
        assertThat(MDC.get(BotMdc.GAME_NAME)).isEqualTo("BauCua");
    }

    @Test
    void clear_removesProduct() {
        BotMdc.set("g-1", 3, "env-1", "116", "BETTING_MINI", "game-1", "BauCua", "bot3");

        BotMdc.clear();

        assertThat(MDC.get(BotMdc.PRODUCT)).isNull();
    }

    @Test
    void setGroupContext_twoArgForm_setsNoProduct() {
        // The 9 legacy call sites keep today's behaviour byte for byte.
        BotMdc.setGroupContext("g-1", "env-1");

        assertThat(MDC.get(BotMdc.BOT_GROUP_ID)).isEqualTo("g-1");
        assertThat(MDC.get(BotMdc.ENVIRONMENT_ID)).isEqualTo("env-1");
        assertThat(MDC.get(BotMdc.PRODUCT)).isNull();
    }

    @Test
    void setGroupContext_threeArgForm_setsProduct_andToleratesNull() {
        BotMdc.setGroupContext("g-1", "env-1", "097");
        assertThat(MDC.get(BotMdc.PRODUCT)).isEqualTo("097");

        BotMdc.clear();
        BotMdc.setGroupContext("g-1", "env-1", null);
        assertThat(MDC.get(BotMdc.PRODUCT)).isNull();
    }

    // ---- PLUGIN_HOT_RELOAD Phase 1: pluginVersion ----

    @Test
    void setPluginVersion_putsTheKey_andClearRemovesIt() {
        BotMdc.set("g-1", 3, "env-1", "116", "BETTING_MINI", "game-1", "BauCua", "bot3");
        BotMdc.setPluginVersion("builtin");

        assertThat(MDC.get(BotMdc.PLUGIN_VERSION)).isEqualTo("builtin");

        BotMdc.clear();

        // Same reason clear() removes product: on a pooled or virtual thread, a leftover
        // version would label the NEXT bot's lines — and mid-drain that is the difference
        // between "this group is half migrated" and "it is not".
        assertThat(MDC.get(BotMdc.PLUGIN_VERSION)).isNull();
    }

    @Test
    void setPluginVersion_skipsNullAndBlank() {
        BotMdc.setPluginVersion(null);
        assertThat(MDC.get(BotMdc.PLUGIN_VERSION)).isNull();

        BotMdc.setPluginVersion("");
        assertThat(MDC.get(BotMdc.PLUGIN_VERSION)).isNull();
    }

    @Test
    void pluginVersion_isNotAGroupLevelKey() {
        // Mid-drain a group is mixed-version, so an aggregated group line must not claim
        // one version for the whole group (PLUGIN_HOT_RELOAD Phase 1 step 1).
        assertThat(BotMdc.GROUP_LEVEL_KEYS).doesNotContain(BotMdc.PLUGIN_VERSION);
    }
}
