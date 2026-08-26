package com.vingame.bot.infrastructure.observability;

import com.vingame.bot.common.plugin.PluginVersionResolver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts plugin classloader <em>instances</em>, because nothing else does
 * (PLUGIN_HOT_RELOAD AD-3).
 * <p>
 * <b>Why this exists.</b> Metaspace bytes and loaded-class counts already ship —
 * {@code jvm_memory_used_bytes{area="nonheap",id="Metaspace"}},
 * {@code jvm_classes_loaded_classes} and {@code jvm_classes_unloaded_classes_total} come
 * free from Spring Boot's {@code JvmMetricsAutoConfiguration}, and Phase 1 adopts rather
 * than rebuilds them (AD-2). What is genuinely missing is the count of loaders: Micrometer
 * has no binder for it and the JVM exposes no MXBean, so a retained-but-dead plugin
 * classloader shows up only as "class count and metaspace never come down" — a symptom
 * that names neither the loader nor the version that leaked it.
 * <p>
 * <b>Why weak references.</b> {@code live} is the leak detector, and it can only be one if
 * it is measuring reachability rather than bookkeeping: after a drop,
 * {@code registered − reclaimed} staying above zero <em>is</em> the retention.
 * {@code jvm_classes_unloaded_classes_total} moving is the JVM-side corroboration.
 * <ul>
 *   <li>{@code plugin_classloaders_live{pluginVersion}} — registered loaders whose
 *       {@link WeakReference} has not been cleared.</li>
 *   <li>{@code plugin_classloaders_registered_total{pluginVersion}} — loaders ever
 *       registered.</li>
 *   <li>{@code plugin_classloaders_reclaimed_total{pluginVersion}} — loaders observed
 *       collected through the {@link ReferenceQueue}.</li>
 * </ul>
 * <b>At Phase 1 the registry holds exactly one entry</b> — the application classloader
 * under {@code builtin} — so a healthy scrape reads {@code live{builtin}=1},
 * {@code registered_total{builtin}=1}, {@code reclaimed_total{builtin}=0}. That is degenerate
 * on purpose (AD-1): the meter names, the tag, the panel and the unit test all exist and
 * are proven before step 4 creates the failure mode they detect. A leak detector shipped
 * in the same release as the leak proves nothing about the release.
 * <p>
 * <b>How to misread these meters.</b> A cleared {@code WeakReference} proves the loader
 * became <em>unreachable</em> and was collected, which is what frees its metaspace; it
 * does not prove any particular class was unloaded, and {@code System.gc()} is a hint, not
 * a command. So {@code live} is a <b>lower bound on retention</b>: read it as "at least
 * this many loaders are still reachable", never as "exactly this many are alive". A loader
 * that has become unreachable but has not yet been collected still counts as live, which
 * is the safe direction — the same lower-bound discipline {@code AsyncQueueMetrics}
 * documents for the log queues.
 * <p>
 * Meter names are deliberately <b>not</b> {@code bot_}-prefixed, for the same reason
 * {@code AsyncQueueMetrics}' are not: {@code BotMdcTagsMeterFilter} would otherwise stamp
 * a JVM-wide fact with whatever MDC the sampler thread inherited.
 */
@Slf4j
@Component
public class PluginClassLoaderMetrics {

    static final String LIVE = "plugin_classloaders_live";

    /**
     * <b>Not {@code plugin_classloaders_created_total}</b>, which is what this was until
     * QA probed it against a real {@code PrometheusMeterRegistry}. {@code _created} is a
     * <em>reserved Prometheus suffix</em> (the OpenMetrics created-timestamp series): the
     * client's name sanitiser strips {@code _total}, then strips {@code _created}, and the
     * counter exposition re-appends {@code _total} — so the meter scraped as
     * {@code plugin_classloaders_total}, a name nobody wrote, no dashboard queried and no
     * verification step grepped. {@link #RECLAIMED} is untouched by the rule, which is
     * exactly what made it look like a typo rather than a rule. Same class of defect as
     * the {@code game_info} → bare {@code game} bug that opened
     * {@code InfoGaugePrometheusScrapeTest}, and that test is now the guard for both:
     * every other test here pins names against a {@code SimpleMeterRegistry}, which
     * applies no naming convention at all. {@code _registered_total} round-trips intact
     * (verified by probe) and pairs with {@code _reclaimed_total}.
     */
    static final String REGISTERED = "plugin_classloaders_registered_total";

    static final String RECLAIMED = "plugin_classloaders_reclaimed_total";

    /** MDC/metric tag key, matching {@code com.vingame.bot.common.logging.BotMdc#PLUGIN_VERSION}. */
    static final String TAG = "pluginVersion";

    /** Sampling cadence, seconds. Matched to the Prometheus scrape interval. */
    static final long SAMPLE_INTERVAL_SECONDS = 10;

    private final MeterRegistry registry;
    private final PluginVersionResolver versionResolver;

    /**
     * Cleared references land here. {@link WeakReference} (not {@link PhantomReference})
     * because the question is "was the loader collected", not "can we act before it is
     * finalized" — and a phantom reference would have to be cleared by hand or it pins the
     * referent, which is the exact bug this class is built to find.
     */
    private final ReferenceQueue<ClassLoader> collected = new ReferenceQueue<>();

    /**
     * Strong refs to the {@link VersionedRef}s themselves. A {@link WeakReference} that is
     * itself unreachable is never enqueued, so without this the queue would silently stay
     * empty and {@code reclaimed_total} would never move — a leak detector that reports
     * "no leaks" by having lost its own bookkeeping.
     */
    private final Set<VersionedRef> tracked = ConcurrentHashMap.newKeySet();

    /** Per-version live count, and the state object the {@code live} gauge reads. */
    private final Map<String, AtomicLong> live = new ConcurrentHashMap<>();

    private ScheduledExecutorService sampler;

    public PluginClassLoaderMetrics(MeterRegistry registry, PluginVersionResolver versionResolver) {
        this.registry = registry;
        this.versionResolver = versionResolver;
    }

    /** A weak reference that remembers which plugin version its referent belonged to. */
    private static final class VersionedRef extends WeakReference<ClassLoader> {
        private final String pluginVersion;

        private VersionedRef(ClassLoader referent, String pluginVersion,
                             ReferenceQueue<ClassLoader> queue) {
            super(referent, queue);
            this.pluginVersion = pluginVersion;
        }
    }

    @PostConstruct
    void start() {
        String version = versionResolver.currentVersion();
        ClassLoader loader = getClass().getClassLoader();
        register(version, loader);

        sampler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("plugin-classloader-sampler").factory());
        sampler.scheduleAtFixedRate(this::drainQuietly, SAMPLE_INTERVAL_SECONDS,
                SAMPLE_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // AD-9: the single new INFO line of this phase, and it fires once per JVM — the
        // same shape and justification as NettyEventLoopConfig's one-shot EventLoopGroup
        // identity line. The identity hash is what makes a later "the loader changed"
        // claim checkable against a log rather than against memory.
        log.info("plugin runtime: version={}, classloader={}",
                version, System.identityHashCode(loader));
    }

    @PreDestroy
    void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
        }
    }

    /**
     * Start accounting for a classloader under a plugin version.
     * <p>
     * Public because step 4's loader factory is the caller that makes this class do
     * anything interesting; in Phase 1 the only caller is {@link #start()}, registering the
     * application classloader under {@code builtin}. Registering the same loader twice
     * double-counts it — deliberately not deduplicated, because "the same version was
     * loaded twice" is a fact worth seeing rather than one worth hiding.
     */
    public void register(String pluginVersion, ClassLoader loader) {
        tracked.add(new VersionedRef(loader, pluginVersion, collected));
        registerMeters(pluginVersion);
        live.get(pluginVersion).incrementAndGet();
        registry.counter(REGISTERED, TAG, pluginVersion).increment();
    }

    /**
     * Register all three meters for a version at zero. Eager, for the reason
     * {@code AsyncQueueMetrics} registers its counters eagerly: an alert (or a panel) over
     * a series that only appears once the incident starts cannot be tested before the
     * incident, and a missing series reads as a healthy one.
     */
    private void registerMeters(String pluginVersion) {
        live.computeIfAbsent(pluginVersion, version -> {
            AtomicLong count = new AtomicLong();
            Gauge.builder(LIVE, count, AtomicLong::doubleValue)
                    // strongReference(true) throughout this codebase: a weakly-held state
                    // object lets the gauge silently start reporting NaN.
                    .strongReference(true)
                    .tag(TAG, version)
                    .description("Registered plugin classloaders whose weak reference has not been "
                            + "cleared — a lower bound on retention, not a census")
                    .register(registry);
            Counter.builder(REGISTERED)
                    .tag(TAG, version)
                    .description("Plugin classloaders ever registered under this version")
                    .register(registry);
            Counter.builder(RECLAIMED)
                    .tag(TAG, version)
                    .description("Plugin classloaders observed collected — registered_total minus "
                            + "this, sustained above zero after a drain, is the leak")
                    .register(registry);
            return count;
        });
    }

    private void drainQuietly() {
        try {
            drain();
        } catch (Exception e) {
            log.error("Plugin-classloader sampling failed: {}", e.getMessage());
        }
    }

    /**
     * Drain everything the GC has enqueued since the last pass and settle the meters.
     * Package-private and free of scheduling so tests drive it directly.
     *
     * @return how many loaders were observed collected in this pass
     */
    int drain() {
        int reclaimed = 0;
        Reference<? extends ClassLoader> ref;
        while ((ref = collected.poll()) != null) {
            if (!(ref instanceof VersionedRef versioned) || !tracked.remove(versioned)) {
                continue;
            }
            live.get(versioned.pluginVersion).decrementAndGet();
            registry.counter(RECLAIMED, TAG, versioned.pluginVersion).increment();
            reclaimed++;
        }
        return reclaimed;
    }

    /** Current live count for a version — the value the {@code live} gauge publishes. */
    long liveCount(String pluginVersion) {
        AtomicLong count = live.get(pluginVersion);
        return count != null ? count.get() : 0;
    }
}
