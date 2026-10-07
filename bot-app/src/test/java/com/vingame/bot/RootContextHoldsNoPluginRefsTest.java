package com.vingame.bot;

import com.vingame.bot.domain.bot.message.MessageTypesRegistry;
import com.vingame.bot.domain.bot.strategy.BettingStrategyFactory;
import com.vingame.bot.domain.bot.strategy.slot.SlotStrategyFactory;
import com.vingame.bot.domain.botgroup.repository.BotGroupRepository;
import com.vingame.bot.infrastructure.plugin.PluginBundle;
import com.vingame.bot.infrastructure.plugin.PluginRegistries;
import com.vingame.bot.infrastructure.plugin.PluginRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PLUGIN_HOT_RELOAD_3_4 <b>L-10</b> (spike rule 3): among the root context's beans, only
 * {@link PluginRuntime} holds a plugin bundle, the registries, a registry, or the bundle's
 * Jackson {@code TypeFactory}.
 * <p>
 * <b>Why.</b> Step 6 releases a plugin version by dropping it from {@code PluginRuntime}
 * and closing it. Any other root bean that kept a reference — a consumer that cached
 * {@code current().bettingStrategies()} in a field "for speed", a bean holding the bundle's
 * type factory — is a GC root for the whole plugin classloader, and the version never
 * unloads. It also defeats step 5, because that consumer keeps serving the old version
 * after a swap. D-9's rule is "read {@code current()} per operation"; this is the build's
 * enforcement of it.
 * <p>
 * <b>How.</b> Every singleton whose class is ours ({@code com.vingame.bot.*}), unwrapped
 * from any AOP proxy, has every declared field of its class hierarchy read reflectively. A
 * field fails if its <em>declared type</em> is one of the forbidden types, or if its
 * <em>value</em> is one of the live forbidden objects (which also catches an
 * {@code Object}- or interface-typed field, and the {@code TypeFactory}, whose type alone
 * proves nothing). Lambdas are beans too — the {@code PluginVersionResolver} bean is one —
 * and their captured arguments are fields, so they are covered: capturing
 * {@code PluginRuntime} is allowed, capturing a registry is not.
 * <p>
 * <b>One level is not enough, so the scan follows values a few hops deep</b> (QA 4a). A
 * root bean can hold a registry without any field of its own holding it: a
 * {@code Supplier} field whose lambda captured {@code current()} instead of the runtime
 * ({@code PluginClassLoaderMetrics.subject} is exactly that shape today, capturing the
 * runtime as it should), or a collection, {@code Optional} or holder object of ours that
 * carries one. {@link #reachForbidden} therefore descends, up to {@value #MAX_DEPTH} hops,
 * into lambdas and other non-bean objects of ours, {@code Collection}s, {@code Map}s,
 * arrays, {@code Optional} and {@code AtomicReference}. It never descends into another
 * root bean (each is inspected on its own) nor into {@code PluginRuntime}, and it never
 * reflects into a class that is not ours.
 * <p>
 * The context configuration is {@link ApplicationContextLoadsTest}'s, verbatim, so the
 * Spring test context cache serves both from one refresh.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/bot-manager-context-test"
                + "?serverSelectionTimeoutMS=200&connectTimeoutMS=200",
        "logging.level.org.mongodb.driver.cluster=OFF",
})
@DisplayName("Only PluginRuntime holds plugin references in the root context (L-10)")
class RootContextHoldsNoPluginRefsTest {

    /** Forbidden as a field's declared type on any root bean but {@link PluginRuntime}. */
    private static final List<Class<?>> FORBIDDEN_TYPES = List.of(
            PluginBundle.class,
            PluginRegistries.class,
            BettingStrategyFactory.class,
            SlotStrategyFactory.class,
            MessageTypesRegistry.class);

    /** The same stub as {@link ApplicationContextLoadsTest}, for the same reason. */
    @MockitoBean
    private BotGroupRepository botGroupRepository;

    @Autowired
    private ConfigurableApplicationContext context;

    /** How many hops below a bean field {@link #reachForbidden} follows. */
    static final int MAX_DEPTH = 4;

    /**
     * Whether {@code value}, or anything reachable from it within {@link #MAX_DEPTH} hops
     * through containers and non-bean objects of ours, is one of the live plugin objects.
     *
     * @return a description of what was reached, or {@code null}.
     */
    static String reachForbidden(Object value, Set<Object> forbidden, Set<Object> beans,
                                 int depth, Set<Object> visited) throws IllegalAccessException {
        if (value == null) {
            return null;
        }
        if (forbidden.contains(value)) {
            String what = value.getClass().getSimpleName();
            return depth == 0 ? what : what + " (" + depth + " hop(s) down)";
        }
        if (beans.contains(value) || depth >= MAX_DEPTH || !visited.add(value)) {
            return null;
        }
        List<Object> children = new ArrayList<>();
        if (value instanceof java.util.Map<?, ?> map) {
            children.addAll(map.keySet());
            children.addAll(map.values());
        } else if (value instanceof java.util.Collection<?> collection) {
            children.addAll(collection);
        } else if (value instanceof Object[] array) {
            children.addAll(java.util.Arrays.asList(array));
        } else if (value instanceof java.util.Optional<?> optional) {
            optional.ifPresent(children::add);
        } else if (value instanceof java.util.concurrent.atomic.AtomicReference<?> ref) {
            children.add(ref.get());
        } else if (value.getClass().getName().startsWith("com.vingame.bot.")
                && !value.getClass().isEnum()) {
            for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                            || !field.trySetAccessible()) {
                        continue;
                    }
                    children.add(field.get(value));
                }
            }
        }
        for (Object child : children) {
            String reached = reachForbidden(child, forbidden, beans, depth + 1, visited);
            if (reached != null) {
                return reached;
            }
        }
        return null;
    }

    @Test
    @DisplayName("no registry, bundle or registries record is itself a root bean")
    void noPluginObjectIsABean() {
        for (Class<?> type : FORBIDDEN_TYPES) {
            assertThat(context.getBeanNamesForType(type))
                    .as("%s must be reached through PluginRuntime.current(), never injected", type.getSimpleName())
                    .isEmpty();
        }
        assertThat(context.getBeanNamesForType(PluginRuntime.class)).hasSize(1);
    }

    @Test
    @DisplayName("no root bean but PluginRuntime has a field holding a plugin reference")
    void onlyPluginRuntimeHoldsPluginReferences() throws IllegalAccessException {
        PluginRuntime runtime = context.getBean(PluginRuntime.class);
        PluginRegistries current = runtime.current();
        Set<Object> forbiddenValues = Collections.newSetFromMap(new IdentityHashMap<>());
        forbiddenValues.add(current);
        forbiddenValues.add(current.bundle());
        forbiddenValues.add(current.bettingStrategies());
        forbiddenValues.add(current.slotStrategies());
        forbiddenValues.add(current.messageTypes());
        forbiddenValues.add(current.typeFactory());

        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        // Every root singleton, unwrapped, plus the runtime: values the deep scan must not
        // walk into (another bean is inspected on its own; the runtime is the allowed holder).
        Set<Object> beans = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : beanFactory.getSingletonNames()) {
            Object raw = beanFactory.getSingleton(name);
            if (raw != null) {
                beans.add(raw);
                beans.add(AopTestUtils.getUltimateTargetObject(raw));
            }
        }
        beans.add(runtime);
        List<String> violations = new ArrayList<>();
        int inspected = 0;
        for (String name : beanFactory.getSingletonNames()) {
            Object raw = beanFactory.getSingleton(name);
            if (raw == null) {
                continue;
            }
            Object bean = AopTestUtils.getUltimateTargetObject(raw);
            if (bean == runtime || !bean.getClass().getName().startsWith("com.vingame.bot.")) {
                continue;
            }
            inspected++;
            for (Class<?> c = bean.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isSynthetic() && !field.getName().startsWith("arg$")) {
                        continue;
                    }
                    String where = name + " (" + c.getName() + "." + field.getName() + ")";
                    for (Class<?> forbidden : FORBIDDEN_TYPES) {
                        if (forbidden.isAssignableFrom(field.getType())) {
                            violations.add(where + " is declared as " + forbidden.getSimpleName());
                        }
                    }
                    if (!field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : bean);
                    String reached = reachForbidden(value, forbiddenValues, beans, 0,
                            Collections.newSetFromMap(new IdentityHashMap<>()));
                    if (reached != null) {
                        violations.add(where + " holds " + reached + " of the live bundle");
                    }
                }
            }
        }

        assertThat(inspected)
                .as("the scan must actually reach our beans, or it passes vacuously")
                .isGreaterThan(50);
        assertThat(violations)
                .as("root beans holding plugin references — read PluginRuntime.current() per "
                        + "operation instead (PLUGIN_HOT_RELOAD_3_4 D-9, L-10)")
                .isEmpty();
    }
}
