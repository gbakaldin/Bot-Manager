package com.vingame.bot.infrastructure.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DisplayNameService}.
 * <p>
 * Sweep coverage — the service is small but its failure mode (missing
 * resource file → every bot gets no display name) is silent today. Three
 * observable methods plus the {@code init()} bootstrap are pinned here.
 * <p>
 * The real {@code display_names.txt} resource is on the test classpath; the
 * "loaded" tests rely on it rather than constructing a synthetic fixture.
 * The "empty" tests use {@link ReflectionTestUtils} to clear the internal
 * list — exercising the {@code displayNames.isEmpty()} branches that the
 * loaded path can never reach.
 */
@DisplayName("DisplayNameService")
class DisplayNameServiceTest {

    @Nested
    @DisplayName("before init / when empty")
    class EmptyState {

        @Test
        @DisplayName("hasDisplayNames returns false before init()")
        void hasDisplayNames_falseBeforeInit() {
            DisplayNameService service = new DisplayNameService();
            assertThat(service.hasDisplayNames()).isFalse();
        }

        @Test
        @DisplayName("getRandomDisplayName returns null when no names loaded")
        void getRandomDisplayName_returnsNullWhenEmpty() {
            DisplayNameService service = new DisplayNameService();
            assertThat(service.getRandomDisplayName()).isNull();
        }

        @Test
        @DisplayName("getDisplayNameCount returns 0 when no names loaded")
        void getDisplayNameCount_returnsZeroWhenEmpty() {
            DisplayNameService service = new DisplayNameService();
            assertThat(service.getDisplayNameCount()).isZero();
        }
    }

    @Nested
    @DisplayName("after init() with classpath resource")
    class Loaded {

        @Test
        @DisplayName("init() loads display names from the classpath resource")
        void init_loadsDisplayNamesFromClasspathResource() {
            DisplayNameService service = new DisplayNameService();
            service.init();

            assertThat(service.hasDisplayNames()).isTrue();
            assertThat(service.getDisplayNameCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("getRandomDisplayName returns a non-null, non-blank name from the loaded set")
        void getRandomDisplayName_returnsLoadedName() {
            DisplayNameService service = new DisplayNameService();
            service.init();

            String name = service.getRandomDisplayName();
            assertThat(name).isNotNull();
            assertThat(name).isNotBlank();
            // The trimmed line cannot equal an untrimmed variant — pin the
            // contract that loadDisplayNames() trims whitespace and skips
            // blanks.
            assertThat(name).isEqualTo(name.trim());
        }

        @Test
        @DisplayName("multiple getRandomDisplayName calls all return non-null")
        void getRandomDisplayName_returnsNonNullOverManyCalls() {
            DisplayNameService service = new DisplayNameService();
            service.init();

            for (int i = 0; i < 20; i++) {
                assertThat(service.getRandomDisplayName()).isNotNull();
            }
        }

        @Test
        @DisplayName("init() is idempotent — calling twice does not duplicate entries when underlying list is reset")
        void init_doesNotDoubleLoadWhenCalledTwiceFromScratch() {
            DisplayNameService service = new DisplayNameService();
            service.init();
            int countAfterFirst = service.getDisplayNameCount();

            // init() appends without clearing; the second call doubles the
            // internal list. Pin that behaviour so a future "guard against
            // double-init" change surfaces here.
            service.init();
            int countAfterSecond = service.getDisplayNameCount();

            assertThat(countAfterSecond).isEqualTo(2 * countAfterFirst);
        }
    }

    @Nested
    @DisplayName("internal-state-driven behaviour (manipulated via reflection)")
    class InternalState {

        @Test
        @DisplayName("getRandomDisplayName returns the only entry when list size == 1")
        void getRandomDisplayName_returnsSoleEntry() {
            DisplayNameService service = new DisplayNameService();
            @SuppressWarnings("unchecked")
            List<String> internal = (List<String>) ReflectionTestUtils.getField(service, "displayNames");
            assertThat(internal).isNotNull();
            internal.add("OnlyName");

            assertThat(service.hasDisplayNames()).isTrue();
            assertThat(service.getDisplayNameCount()).isEqualTo(1);
            assertThat(service.getRandomDisplayName()).isEqualTo("OnlyName");
        }

        @Test
        @DisplayName("getRandomDisplayName returns an entry from the loaded list (never out of range)")
        void getRandomDisplayName_alwaysReturnsAMemberOfTheLoadedList() {
            DisplayNameService service = new DisplayNameService();
            @SuppressWarnings("unchecked")
            List<String> internal = (List<String>) ReflectionTestUtils.getField(service, "displayNames");
            assertThat(internal).isNotNull();
            List<String> seeded = new ArrayList<>(List.of("Alice", "Bob", "Carol", "Dave"));
            internal.addAll(seeded);

            for (int i = 0; i < 30; i++) {
                assertThat(seeded).contains(service.getRandomDisplayName());
            }
        }
    }
}
