package com.vingame.bot.infrastructure.logging;

import com.vingame.bot.common.logging.ScopedDebugFilter;
import com.vingame.bot.common.logging.ScopedDebugRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.Filterable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.beans.PropertyChangeListener;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Installs the {@link ScopedDebugFilter} onto the live log4j2 {@code Configuration} and
 * keeps it there (LOG_VOLUME_TIERING AD-9), plus runs the TTL sweep.
 * <p>
 * <b>Programmatic, not {@code @Plugin}.</b> {@code pom.xml} pins
 * {@code annotationProcessorPaths} to mapstruct + lombok, which turns off classpath scanning
 * for annotation processors; log4j2's plugin processor therefore never runs and a
 * {@code @Plugin}-annotated filter would have no {@code Log4j2Plugins.dat} entry in the fat
 * jar — it would be silently absent in production and present in the IDE. So the filter is
 * attached at runtime: {@code (LoggerContext) LogManager.getContext(false)} →
 * {@code getConfiguration()} → {@code addFilter} → {@code updateLoggers()}.
 * <p>
 * <b>Attach point — a correction to AD-9, verified against log4j-core 2.24.1.</b> The plan
 * says {@code config.getLoggerConfig("com.vingame.bot").addFilter(filter)}. That does not
 * work: the filter consulted <em>before</em> the level check is the {@code Configuration}'s
 * ({@code Logger.PrivateConfig.filter} → {@code config.getFilter()}, Logger.java:586), while
 * a LoggerConfig's own filter is applied only later by {@code LoggerConfig.log(LogEvent)} —
 * after the level gate has already discarded the DEBUG event, where nothing but a
 * {@code DENY} could still have an effect. On the LoggerConfig the filter installs cleanly,
 * reports healthy, and does nothing at all. The filter therefore goes on the
 * {@code Configuration}, and {@code ScopedDebugFilter.SCOPED_LOGGER_PREFIX} restores the
 * blast-radius limit the plan got from naming a single LoggerConfig.
 * <p>
 * <b>Ordering.</b> {@code @PostConstruct} runs during bean creation, which is long after
 * Spring Boot's {@code LoggingApplicationListener} has initialized the logging system and
 * applied {@code logging.level.*} (both happen on {@code ApplicationEnvironmentPreparedEvent},
 * before the context refreshes). And the level path used by {@code LOGGING_LEVEL_COM_VINGAME_BOT}
 * and by {@code /actuator/loggers} mutates the existing LoggerConfig <em>in place</em>
 * ({@code Configurator.setLevel}), so it preserves filters exactly as it preserves
 * {@code additivity=false} and the appenderRefs. What would drop the filter is a full
 * {@code reconfigure()} that rebuilds LoggerConfigs from the configuration — so this class
 * also listens for the LoggerContext's {@code config} property change and re-installs.
 * {@code ScopedDebugFilterInstallationTest} pins both halves.
 * <p>
 * <b>The application logger must still exist.</b> The filter promotes events for loggers
 * under {@code com.vingame.bot}; if {@code log4j2.properties} stopped declaring a
 * LoggerConfig by that exact name, the promoted events would route through root instead —
 * a different appender set and, with root at INFO, a different meaning. That is not fatal,
 * so it is a WARN at startup rather than a refusal, but it is the one configuration change
 * that quietly alters what scoped DEBUG does.
 */
@Slf4j
@Component
public class ScopedDebugInstaller {

    /** The application logger the filter promotes events for — see the class javadoc. */
    static final String APP_LOGGER = "com.vingame.bot";

    /** TTL sweep cadence. Expiry is also enforced lazily on read, so this is a backstop. */
    static final long SWEEP_INTERVAL_SECONDS = 30;

    private final ScopedDebugRegistry registry;
    private final boolean enabled;
    private final ScopedDebugFilter filter;

    private ScheduledExecutorService sweeper;
    private volatile boolean installed;
    /** Re-entrancy guard — see {@link #install()}. */
    private final AtomicBoolean installing = new AtomicBoolean();
    /**
     * The reconfiguration listener, held so {@link #stop()} can remove it.
     * <p>
     * It is registered on the <b>JVM-global</b> {@code LoggerContext}, which outlives this
     * bean. Left behind, a stale listener from a torn-down context fires on the next
     * reconfiguration and runs that dead installer's {@code install()} — which begins by
     * stripping <em>any</em> {@code ScopedDebugFilter} from the live configuration, including
     * the one belonging to the current Spring context, and attaches its own, bound to a
     * registry {@code stop()} has already {@code clear()}ed. The live installer is then
     * {@code installed == true} with {@code isAttached() == false}: every POST answers 200
     * and promotes nothing, which is exactly the failure the "armed" line exists to rule out.
     */
    private volatile PropertyChangeListener reconfigurationListener;

    public ScopedDebugInstaller(ScopedDebugRegistry registry,
                                @Value("${bot.logging.scoped-debug.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
        this.filter = new ScopedDebugFilter(registry);
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Scoped per-group DEBUG is disabled (bot.logging.scoped-debug.enabled=false)");
            return;
        }
        install();
        sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("scoped-debug-sweeper").factory());
        sweeper.scheduleAtFixedRate(this::sweepQuietly,
                SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        // Detach on shutdown so a filter instance bound to a dead context's registry can
        // never linger on the (JVM-global) LoggerContext -- and take the listener with it,
        // or the dead installer keeps a live hook into that context (see the field javadoc).
        PropertyChangeListener listener = reconfigurationListener;
        if (listener != null) {
            context().removePropertyChangeListener(listener);
            reconfigurationListener = null;
        }
        removeExistingFilters(context().getConfiguration());
        registry.clear();
        installed = false;
    }

    /**
     * Attach the filter, replacing any previous instance. Idempotent: safe to call again
     * after a reconfiguration, and safe to call twice.
     * <p>
     * <b>Re-entrancy is not theoretical here.</b> {@code LoggerContext.updateLoggers()}
     * itself fires the {@code config} property-change event, so the "re-install after a
     * reconfiguration" listener below is invoked by our own install — without the guard
     * that is an immediate {@code StackOverflowError} at context startup, which is how this
     * was found.
     */
    void install() {
        if (!installing.compareAndSet(false, true)) {
            return;
        }
        try {
            Configuration configuration = context().getConfiguration();
            removeExistingFilters(configuration);
            filter.start();
            configuration.addFilter(filter);
            context().updateLoggers();
            if (!installed) {
                warnIfApplicationLoggerMissing(configuration);
                // One line, once, so an operator can confirm from the log that the mechanism
                // is armed on this instance — the failure mode otherwise is a REST call that
                // returns 200 and changes nothing.
                log.info("Scoped per-group DEBUG armed on logger {} (max {} concurrent scopes, sweep {}s)",
                        APP_LOGGER, registry.getMaxScopes(), SWEEP_INTERVAL_SECONDS);
                PropertyChangeListener listener = event -> {
                    if (LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())
                            && !isAttached()) {
                        // The configuration was rebuilt (reconfigure / config file reload):
                        // the LoggerConfigs are new objects and our filter went with the old
                        // ones. The isAttached() check keeps the far more common event —
                        // an ordinary updateLoggers() — free.
                        log.debug("Log4j2 configuration was rebuilt — re-installing the scoped-debug filter");
                        install();
                    }
                };
                reconfigurationListener = listener;
                context().addPropertyChangeListener(listener);
            }
            installed = true;
        } finally {
            installing.set(false);
        }
    }

    /** Is our filter currently on the live Configuration? */
    boolean isAttached() {
        Filter current = context().getConfiguration().getFilter();
        if (current == filter) {
            return true;
        }
        if (current instanceof CompositeFilter composite) {
            for (Filter each : composite.getFiltersArray()) {
                if (each == filter) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One sweep pass — the package-private seam so tests drive it without the 30 s wait. */
    void sweepOnce() {
        sweepQuietly();
    }

    private void sweepQuietly() {
        try {
            // Re-assert attachment first. install() drops a concurrent request on the
            // `installing` CAS rather than retrying, and nothing else re-checks, so without
            // this a single lost re-install is PERMANENT detachment that the REST surface
            // would have to report as unarmed forever. Here it self-heals within one sweep.
            if (installed && !isAttached()) {
                log.warn("The scoped-debug filter is no longer attached to the log4j2 "
                        + "configuration — re-installing");
                install();
            }
            List<String> expired = registry.sweep();
            for (String botGroupId : expired) {
                log.info("scoped debug expired for group {} — back to the configured level", botGroupId);
            }
        } catch (Exception e) {
            log.error("Scoped-debug sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Whether scoped DEBUG would actually do something — read by the REST surface and by
     * tests. Deliberately {@code installed && isAttached()} rather than the flag alone:
     * the flag only records that an {@code install()} once succeeded, and a stale installer
     * from another Spring context can strip this one's filter afterwards. Reporting the flag
     * would then answer 200 to a POST that promotes nothing, and
     * {@code ScopedDebugStatusDTO.enabled} would be documenting a guarantee it does not
     * carry.
     */
    public boolean isInstalled() {
        return installed && isAttached();
    }

    private static LoggerContext context() {
        return (LoggerContext) LogManager.getContext(false);
    }

    /**
     * Scoped DEBUG promotes events for loggers under {@code com.vingame.bot}; if the
     * configuration stops declaring a LoggerConfig by that exact name they route through
     * root instead. Worth a WARN, not a refusal.
     */
    private void warnIfApplicationLoggerMissing(Configuration configuration) {
        LoggerConfig config = configuration.getLoggerConfig(APP_LOGGER);
        if (config == null || !APP_LOGGER.equals(config.getName())) {
            log.warn("log4j2 declares no LoggerConfig named '{}' (got '{}') — scoped per-group "
                            + "DEBUG will still fire, but through the root logger's appenders. "
                            + "Check log4j2.properties.",
                    APP_LOGGER, config != null ? config.getName() : null);
        }
    }

    /**
     * Drop any previously attached {@link ScopedDebugFilter}. {@code addFilter} composes
     * rather than replaces, so without this a re-install would leave a
     * {@link CompositeFilter} holding several equivalent filters.
     */
    private void removeExistingFilters(Filterable filterable) {
        if (filterable == null) {
            return;
        }
        Filter current = filterable.getFilter();
        if (current instanceof ScopedDebugFilter) {
            filterable.removeFilter(current);
        } else if (current instanceof CompositeFilter composite) {
            for (Filter each : composite.getFiltersArray()) {
                if (each instanceof ScopedDebugFilter) {
                    filterable.removeFilter(each);
                }
            }
        }
    }
}
