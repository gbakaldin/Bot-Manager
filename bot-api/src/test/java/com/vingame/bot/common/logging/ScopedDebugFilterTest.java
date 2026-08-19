package com.vingame.bot.common.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LOG_VOLUME_TIERING AD-10 — the filter answers {@code ACCEPT} for a scoped group and
 * {@code NEUTRAL} for everything else, <b>never {@code DENY}</b>.
 * <p>
 * Why each of those matters:
 * <ul>
 *   <li>{@code ACCEPT} is the whole mechanism — a LoggerConfig's filter runs before its
 *       level check, so ACCEPT is what surfaces a DEBUG event through an INFO logger.</li>
 *   <li>{@code NEUTRAL} for everyone else is what leaves the normal level rules alone. A
 *       filter that answered anything else would own the delivery of every line in the
 *       application, WARN and ERROR included — which are coupled to alerting.</li>
 *   <li>Every {@code filter(...)} overload is checked because {@code AbstractFilter}
 *       defaults each one to NEUTRAL <em>independently</em>: a missed override is not a
 *       compile error, it is a filter that silently does nothing for the call shape the
 *       slf4j binding happens to use.</li>
 * </ul>
 */
@DisplayName("ScopedDebugFilter (AD-10)")
class ScopedDebugFilterTest {

    private final ScopedDebugRegistry registry = new ScopedDebugRegistry(10);
    private final ScopedDebugFilter filter = new ScopedDebugFilter(registry);

    @AfterEach
    void tearDown() {
        ThreadContext.clearAll();
        registry.clear();
    }

    @Test
    @DisplayName("with no scope active, everything is NEUTRAL — including for a tagged thread")
    void neutralWhenNothingIsScoped() {
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g1");

        assertThat(decide(Level.DEBUG)).isEqualTo(Filter.Result.NEUTRAL);
        assertThat(decide(Level.INFO)).isEqualTo(Filter.Result.NEUTRAL);
        assertThat(decide(Level.ERROR)).isEqualTo(Filter.Result.NEUTRAL);
    }

    @Test
    @DisplayName("a DEBUG event from a scoped group is ACCEPTed")
    void acceptsDebugForScopedGroup() {
        registry.enable("g1", Duration.ofMinutes(5));
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g1");

        assertThat(decide(Level.DEBUG)).isEqualTo(Filter.Result.ACCEPT);
    }

    @Test
    @DisplayName("a DEBUG event from any OTHER group is NEUTRAL, never DENY")
    void neutralForUnscopedGroup() {
        registry.enable("g1", Duration.ofMinutes(5));
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g2");

        assertThat(decide(Level.DEBUG)).isEqualTo(Filter.Result.NEUTRAL);
    }

    @Test
    @DisplayName("a thread with no botGroupId is NEUTRAL even while a scope is open")
    void neutralWithoutMdc() {
        registry.enable("g1", Duration.ofMinutes(5));

        assertThat(decide(Level.DEBUG)).isEqualTo(Filter.Result.NEUTRAL);
    }

    @Test
    @DisplayName("TRACE is NOT promoted by a DEBUG scope")
    void traceIsNotPromoted() {
        registry.enable("g1", Duration.ofMinutes(5));
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g1");

        // TRACE carries the raw per-frame WebSocket dumps. Promoting it alongside DEBUG
        // would turn a scoped drill-in back into the flood this plan exists to remove, so
        // it stays a deliberate, global /actuator/loggers action.
        assertThat(decide(Level.TRACE)).isEqualTo(Filter.Result.NEUTRAL);
    }

    @Test
    @DisplayName("the scope expiring puts the group straight back to NEUTRAL")
    void expiryEndsTheAccept() {
        // Its own registry, on a manual clock, so the expiry is asserted rather than raced.
        AtomicLong now = new AtomicLong(1_000L);
        ScopedDebugRegistry expiring = new ScopedDebugRegistry(10, now::get);
        ScopedDebugFilter expiringFilter = new ScopedDebugFilter(expiring);
        expiring.enable("g1", Duration.ofMinutes(5));
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g1");

        assertThat(expiringFilter.filter(null, Level.DEBUG, null, "m", "a"))
                .isEqualTo(Filter.Result.ACCEPT);

        now.addAndGet(Duration.ofMinutes(6).toMillis());

        assertThat(expiringFilter.filter(null, Level.DEBUG, null, "m", "a"))
                .isEqualTo(Filter.Result.NEUTRAL);
    }

    @Test
    @DisplayName("EVERY filter overload agrees — AbstractFilter defaults each one separately")
    void everyOverloadIsWired() throws Exception {
        registry.enable("g1", Duration.ofMinutes(5));
        ThreadContext.put(BotMdc.BOT_GROUP_ID, "g1");

        List<String> notAccepting = new ArrayList<>();
        for (Method method : ScopedDebugFilter.class.getMethods()) {
            if (!method.getName().equals("filter") || method.getParameterCount() < 5) {
                continue; // the LogEvent overload is covered by its own test below
            }
            Object[] args = new Object[method.getParameterCount()];
            args[0] = null;                 // Logger
            args[1] = Level.DEBUG;          // Level
            args[2] = null;                 // Marker
            Class<?>[] types = method.getParameterTypes();
            args[3] = types[3] == org.apache.logging.log4j.message.Message.class
                    ? new SimpleMessage("m") : "m";
            for (int i = 4; i < args.length; i++) {
                if (types[i].isArray()) {
                    args[i] = new Object[0];
                } else if (Throwable.class.isAssignableFrom(types[i])) {
                    args[i] = null;
                } else {
                    args[i] = "p";
                }
            }
            if (filter.filter(null, Level.DEBUG, null, "m") != Filter.Result.ACCEPT
                    || method.invoke(filter, args) != Filter.Result.ACCEPT) {
                notAccepting.add(method.toString());
            }
        }

        assertThat(notAccepting)
                .as("an un-overridden overload silently returns NEUTRAL forever")
                .isEmpty();
    }

    @Test
    @DisplayName("the LogEvent overload reads the EVENT's context, not the current thread's")
    void logEventOverloadUsesEventContext() {
        registry.enable("g1", Duration.ofMinutes(5));
        // Deliberately no ThreadContext here: after the AsyncAppender hand-off the event is
        // handled on a thread with an empty context, and the group id must still be found.
        SortedArrayStringMap contextData = new SortedArrayStringMap();
        contextData.putValue(BotMdc.BOT_GROUP_ID, "g1");

        assertThat(filter.filter(event(Level.DEBUG, contextData))).isEqualTo(Filter.Result.ACCEPT);

        SortedArrayStringMap otherGroup = new SortedArrayStringMap();
        otherGroup.putValue(BotMdc.BOT_GROUP_ID, "g2");
        assertThat(filter.filter(event(Level.DEBUG, otherGroup))).isEqualTo(Filter.Result.NEUTRAL);
        assertThat(filter.filter(event(Level.TRACE, contextData))).isEqualTo(Filter.Result.NEUTRAL);
    }

    private static Log4jLogEvent event(Level level, SortedArrayStringMap contextData) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.vingame.bot.Test")
                .setLevel(level)
                .setMessage(new SimpleMessage("m"))
                .setContextData(contextData)
                .build();
    }

    /** The shape the slf4j → log4j2 binding actually reaches. */
    private Filter.Result decide(Level level) {
        return filter.filter(null, level, null, "message {}", "arg");
    }
}
