package com.vingame.bot.common.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * LOG_VOLUME_TIERING AD-9 / AD-10 — the log4j2 filter that lets a single {@code botGroupId}
 * emit DEBUG through a logger whose configured level is INFO.
 * <p>
 * <b>Why a filter and not a level.</b> {@code Logger.PrivateConfig.filter} consults a filter
 * <em>before</em> the level check and short-circuits on a non-{@code NEUTRAL} result, so an
 * {@code ACCEPT} here surfaces a DEBUG event that the INFO level would otherwise have
 * discarded — without touching the level, and therefore without affecting any other group.
 * <p>
 * <b>Correction to LOG_VOLUME_TIERING AD-9/AD-10, verified against log4j-core 2.24.1.</b>
 * The plan specifies attaching this filter to the {@code com.vingame.bot} <em>LoggerConfig</em>,
 * on the premise that "a LoggerConfig's filter runs before the level check". It does not.
 * {@code Logger.PrivateConfig.filter} (Logger.java:586 in 2.24.1) reads
 * {@code config.getFilter()} where {@code config} is the <b>{@code Configuration}</b>, not
 * {@code loggerConfig}; a LoggerConfig's own filter is only consulted afterwards, by
 * {@code LoggerConfig.log(LogEvent)}, at which point the level gate has already dropped the
 * event and only a {@code DENY} could still matter. Attached to the LoggerConfig this filter
 * is therefore inert for its entire purpose — it compiles, installs, reports healthy and
 * changes nothing. So it is attached to the {@code Configuration} instead (see
 * {@code ScopedDebugInstaller}), and the blast radius the plan achieved by picking one
 * LoggerConfig is preserved here instead, by {@link #SCOPED_LOGGER_PREFIX}: a Configuration
 * filter is consulted for every logger in the JVM, and scoped DEBUG has no business
 * promoting the Mongo driver's or Netty's DEBUG lines.
 * <p>
 * <b>Never {@code DENY}</b> (AD-10). Every group that is not scoped gets {@code NEUTRAL},
 * which leaves the normal level rules exactly as they were. A {@code DENY} would make this
 * filter responsible for the delivery of every line in the application, including WARN and
 * ERROR — an alerting-coupled blast radius that a debugging aid has no business having.
 * <p>
 * <b>TRACE is deliberately not promoted.</b> The condition is "DEBUG or more specific", so
 * TRACE falls through to {@code NEUTRAL} and stays governed by the configured level. TRACE
 * carries the raw per-frame WebSocket dumps; promoting it along with DEBUG would turn a
 * scoped drill-in into precisely the flood this plan exists to remove. TRACE stays a global,
 * deliberate {@code /actuator/loggers} action.
 * <p>
 * <b>Installed programmatically, never as a {@code @Plugin}</b> (AD-9). The build pins
 * {@code annotationProcessorPaths} to mapstruct + lombok, which disables classpath scanning
 * for annotation processors — log4j2's plugin processor never runs, so a {@code @Plugin} on
 * this class would produce no {@code Log4j2Plugins.dat} entry and the filter would simply
 * not exist inside the fat jar. {@code ScopedDebugInstaller} adds the instance to the
 * LoggerConfig at runtime instead.
 * <p>
 * <b>Cost.</b> With no scope active the whole filter is one volatile read
 * ({@link ScopedDebugRegistry#isAnyEnabled()}). {@code ThreadContext.get} is only reached
 * once a scope exists, and is a direct read of the thread's context map — no MDC copy.
 */
public final class ScopedDebugFilter extends AbstractFilter {

    /**
     * Only loggers under this prefix can be promoted. The filter hangs off the
     * {@code Configuration}, which every logger consults, so without this a scoped group's
     * thread would also surface DEBUG from every third-party logger it happens to call into.
     */
    public static final String SCOPED_LOGGER_PREFIX = "com.vingame.bot";

    private final ScopedDebugRegistry registry;

    public ScopedDebugFilter(ScopedDebugRegistry registry) {
        // onMatch/onMismatch are unused (decide() returns explicitly), but the pair
        // documents the contract to anything that introspects the filter.
        super(Result.ACCEPT, Result.NEUTRAL);
        this.registry = registry;
    }

    public ScopedDebugRegistry getRegistry() {
        return registry;
    }

    /**
     * The decision, for every call shape that reaches a filter before the event exists.
     * Reads {@code botGroupId} straight off the calling thread's context.
     *
     * @param logger the logger the call came through; {@code null} (only reachable from a
     *               direct unit-test call) skips the prefix gate
     */
    private Result decide(Logger logger, Level level) {
        if (!registry.isAnyEnabled()) {
            return Result.NEUTRAL;
        }
        if (level == null || !level.isMoreSpecificThan(Level.DEBUG)) {
            return Result.NEUTRAL;
        }
        if (!isScopedLogger(logger == null ? null : logger.getName())) {
            return Result.NEUTRAL;
        }
        String botGroupId = ThreadContext.get(BotMdc.BOT_GROUP_ID);
        return botGroupId != null && registry.isEnabled(botGroupId) ? Result.ACCEPT : Result.NEUTRAL;
    }

    private static boolean isScopedLogger(String loggerName) {
        return loggerName == null || loggerName.startsWith(SCOPED_LOGGER_PREFIX);
    }

    /**
     * The decision for an already-built event. Same rule, but the group id comes from the
     * event's own context data: the event may be handled on a different thread than the one
     * that created it (the AsyncAppender hand-off), where {@code ThreadContext} is empty.
     */
    private Result decide(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        if (!registry.isAnyEnabled()) {
            return Result.NEUTRAL;
        }
        Level level = event.getLevel();
        if (level == null || !level.isMoreSpecificThan(Level.DEBUG)) {
            return Result.NEUTRAL;
        }
        if (!isScopedLogger(event.getLoggerName())) {
            return Result.NEUTRAL;
        }
        String botGroupId = event.getContextData() == null
                ? null : event.getContextData().getValue(BotMdc.BOT_GROUP_ID);
        return botGroupId != null && registry.isEnabled(botGroupId) ? Result.ACCEPT : Result.NEUTRAL;
    }

    // ---- Filter overloads.
    // AbstractFilter defaults EVERY one of these to NEUTRAL independently, so an
    // un-overridden shape is a silently inert filter. slf4j's log4j2 binding reaches the
    // (String, Object...) form; the LogEvent form is re-evaluated by LoggerConfig.log().
    // All of them are overridden so the answer cannot depend on the call shape.

    @Override
    public Result filter(LogEvent event) {
        return decide(event);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object p0, Object p1) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4, Object p5) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4, Object p5,
                         Object p6) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4, Object p5,
                         Object p6, Object p7) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4, Object p5,
                         Object p6, Object p7, Object p8) {
        return decide(logger, level);
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg,
                         Object p0, Object p1, Object p2, Object p3, Object p4, Object p5,
                         Object p6, Object p7, Object p8, Object p9) {
        return decide(logger, level);
    }

    @Override
    public String toString() {
        return "ScopedDebugFilter(scopes=" + registry.activeScopes().size() + ")";
    }
}
