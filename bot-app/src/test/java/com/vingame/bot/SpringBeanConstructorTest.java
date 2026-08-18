package com.vingame.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Static guard against the one DI mistake that is invisible everywhere except at context
 * startup: a component with more than one constructor and no {@code @Autowired} to say
 * which one Spring should use.
 * <p>
 * Spring auto-selects a constructor only when a class declares exactly one. With two
 * candidates and no annotation it silently falls back to the no-arg constructor — and if
 * there isn't one, the context dies with
 * {@code BeanInstantiationException: No default constructor found}. That is what happened
 * to {@code VipTalkClient} on the 2026-08-18 staging deploy: a package-private test seam
 * was added next to the {@code @Value} constructor, every unit test kept passing because
 * they all used the seam directly, and the bean was unbuildable.
 * <p>
 * {@link ApplicationContextLoadsTest} catches this too, and catches much more besides —
 * this test is the cheaper, sharper one. It scans <em>every</em> stereotype-annotated class
 * on the classpath (bot-app, bot-engine, bot-api, bot-strategies, bot-messages), including
 * beans a given context happens not to instantiate, and names the offending class and its
 * constructors instead of asking a reader to unpick a nested Spring stack trace. The
 * intended workflow is that this fails first and explains why.
 */
@DisplayName("Spring components declare an unambiguous constructor")
class SpringBeanConstructorTest {

    private static final String BASE_PACKAGE = "com.vingame.bot";

    @Test
    @DisplayName("no component leaves Spring to guess between several constructors")
    void everyComponentHasAConstructorSpringCanChoose() {
        List<String> offenders = new ArrayList<>();

        for (Class<?> type : scanForComponents()) {
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length < 2) {
                continue;
            }

            boolean annotated = false;
            boolean hasNoArg = false;
            for (Constructor<?> constructor : constructors) {
                annotated |= constructor.isAnnotationPresent(Autowired.class);
                hasNoArg |= constructor.getParameterCount() == 0;
            }
            // A no-arg constructor is not flagged: Spring will use it deterministically.
            // Whether that is the *intended* one is a design question this test cannot
            // answer; the failure mode being guarded here is the unbuildable bean.
            if (annotated || hasNoArg) {
                continue;
            }

            List<String> signatures = new ArrayList<>();
            for (Constructor<?> constructor : constructors) {
                signatures.add(describe(constructor));
            }
            offenders.add(type.getName() + " declares " + constructors.length
                    + " constructors and none is @Autowired: " + signatures);
        }

        assertThat(offenders)
                .as("Spring cannot pick a constructor for these components, so the context "
                        + "will fail to start. Annotate the intended one with @Autowired.")
                .isEmpty();
    }

    private static Set<Class<?>> scanForComponents() {
        // useDefaultFilters=true picks up @Component and everything meta-annotated with it:
        // @Service, @Repository, @Controller, @RestController, @Configuration.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);
        // Abstract classes and interfaces are never instantiated by the container.
        scanner.addExcludeFilter((reader, factory) -> !reader.getClassMetadata().isConcrete());

        Set<BeanDefinition> definitions = scanner.findCandidateComponents(BASE_PACKAGE);
        assertThat(definitions)
                .as("the scan itself must find something — an empty scan would make this "
                        + "test pass vacuously forever")
                .isNotEmpty();

        Set<Class<?>> types = new java.util.LinkedHashSet<>();
        for (BeanDefinition definition : definitions) {
            try {
                Class<?> type = ClassUtils.forName(
                        definition.getBeanClassName(), SpringBeanConstructorTest.class.getClassLoader());
                if (!Modifier.isAbstract(type.getModifiers())) {
                    types.add(type);
                }
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException(
                        "scanned but could not load " + definition.getBeanClassName(), e);
            }
        }
        return types;
    }

    private static String describe(Constructor<?> constructor) {
        List<String> parameters = new ArrayList<>();
        for (Class<?> parameterType : constructor.getParameterTypes()) {
            parameters.add(parameterType.getSimpleName());
        }
        return Modifier.toString(constructor.getModifiers() & Modifier.constructorModifiers())
                + " (" + String.join(", ", parameters) + ")";
    }
}
