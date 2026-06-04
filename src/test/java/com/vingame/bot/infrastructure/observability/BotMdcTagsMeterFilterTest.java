package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.logging.BotMdc;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@link BotMdcTagsMeterFilter} merge logic in isolation: ensure it
 * attaches MDC-derived tags only for {@code bot_*} meters, skips aggregate gauges
 * and non-bot meters, and preserves any pre-existing tags on the Meter.Id.
 */
class BotMdcTagsMeterFilterTest {

    private BotMdcTagsMeterFilter filter;

    @BeforeEach
    void setUp() {
        filter = new BotMdcTagsMeterFilter();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    private Meter.Id id(String name, Tags tags) {
        return new Meter.Id(name, tags, null, null, Meter.Type.COUNTER);
    }

    @Test
    void map_botMetric_attachesMdcTags() {
        MDC.put(BotMdc.BOT_GROUP_ID, "g1");
        MDC.put(BotMdc.ENVIRONMENT_ID, "e1");
        MDC.put(BotMdc.GAME_TYPE, "BauCua");

        Meter.Id mapped = filter.map(id("bot_messages_total", Tags.of("cmd", "endGame")));

        assertThat(mapped.getTags()).extracting("key", "value")
                .contains(
                        org.assertj.core.groups.Tuple.tuple("cmd", "endGame"),
                        org.assertj.core.groups.Tuple.tuple(BotMdc.BOT_GROUP_ID, "g1"),
                        org.assertj.core.groups.Tuple.tuple(BotMdc.ENVIRONMENT_ID, "e1"),
                        org.assertj.core.groups.Tuple.tuple(BotMdc.GAME_TYPE, "BauCua")
                );
    }

    @Test
    void map_aggregateGauge_doesNotAttachMdcTags() {
        MDC.put(BotMdc.BOT_GROUP_ID, "g1");
        MDC.put(BotMdc.ENVIRONMENT_ID, "e1");

        for (String name : new String[]{
                "bot_groups_running", "bots_managed", "ws_connections_open", "bots_by_status",
                "bots_dead_currently", "groups_dead_currently",
                "game_total_winnings_total", "game_total_bet_amount_total"}) {
            Meter.Id mapped = filter.map(id(name, Tags.empty()));
            boolean hasGroup = mapped.getTags().stream()
                    .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
            boolean hasEnv = mapped.getTags().stream()
                    .anyMatch(t -> BotMdc.ENVIRONMENT_ID.equals(t.getKey()));
            assertThat(hasGroup).as("aggregate gauge %s must not get botGroupId", name).isFalse();
            assertThat(hasEnv).as("aggregate gauge %s must not get environmentId", name).isFalse();
        }
    }

    @Test
    void map_gameTotalMeter_withGameTypePreTagged_keepsGameTypeButRejectsBotIdentity() {
        // BotMetrics applies gameType directly via gameTypeTagOnly(); the filter
        // allow-list (defense-in-depth) ensures botGroupId/environmentId are NOT
        // auto-stamped even if MDC is populated when a game_total_* meter is created.
        MDC.put(BotMdc.BOT_GROUP_ID, "g1");
        MDC.put(BotMdc.ENVIRONMENT_ID, "e1");
        MDC.put(BotMdc.GAME_TYPE, "BauCua");

        for (String name : new String[]{"game_total_winnings_total", "game_total_bet_amount_total"}) {
            Meter.Id mapped = filter.map(id(name, Tags.of(BotMdc.GAME_TYPE, "BauCua")));
            var keys = mapped.getTags().stream().map(t -> t.getKey()).toList();
            assertThat(keys).as("%s keeps gameType pre-tagged by BotMetrics", name)
                    .contains(BotMdc.GAME_TYPE);
            assertThat(keys).as("%s does NOT receive botGroupId from MDC", name)
                    .doesNotContain(BotMdc.BOT_GROUP_ID, BotMdc.ENVIRONMENT_ID);
        }
    }

    @Test
    void map_nonBotMetric_passesThroughUnchanged() {
        MDC.put(BotMdc.BOT_GROUP_ID, "g1");
        MDC.put(BotMdc.ENVIRONMENT_ID, "e1");

        Meter.Id original = id("jvm_memory_used", Tags.of("area", "heap"));
        Meter.Id mapped = filter.map(original);

        assertThat(mapped).isEqualTo(original);
    }

    @Test
    void map_emptyMdc_returnsIdUnchanged() {
        Meter.Id original = id("bot_messages_total", Tags.of("cmd", "subscribe"));
        Meter.Id mapped = filter.map(original);
        assertThat(mapped).isEqualTo(original);
    }

    @Test
    void map_partialMdc_attachesOnlyPresentTags() {
        MDC.put(BotMdc.BOT_GROUP_ID, "g1");
        // environmentId and gameType absent

        Meter.Id mapped = filter.map(id("bot_failures_total", Tags.empty()));

        boolean hasGroup = mapped.getTags().stream()
                .anyMatch(t -> BotMdc.BOT_GROUP_ID.equals(t.getKey()));
        boolean hasEnv = mapped.getTags().stream()
                .anyMatch(t -> BotMdc.ENVIRONMENT_ID.equals(t.getKey()));
        boolean hasGame = mapped.getTags().stream()
                .anyMatch(t -> BotMdc.GAME_TYPE.equals(t.getKey()));

        assertThat(hasGroup).isTrue();
        assertThat(hasEnv).isFalse();
        assertThat(hasGame).isFalse();
    }
}
