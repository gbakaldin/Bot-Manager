package com.vingame.bot.infrastructure.runtime;

import com.vingame.bot.domain.bot.core.Bot;
import com.vingame.bot.domain.botgroup.model.BotGroupPlayingStatus;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BotGroupRuntime")
class BotGroupRuntimeTest {

    @SuppressWarnings("unchecked")
    private static void setBotInstances(BotGroupRuntime runtime, List<Bot> bots) throws Exception {
        Field f = BotGroupRuntime.class.getDeclaredField("botInstances");
        f.setAccessible(true);
        List<Bot> list = (List<Bot>) f.get(runtime);
        list.clear();
        list.addAll(bots);
    }

    @SuppressWarnings("unchecked")
    private static void setBotFutures(BotGroupRuntime runtime, List<Future<?>> futures) throws Exception {
        Field f = BotGroupRuntime.class.getDeclaredField("botFutures");
        f.setAccessible(true);
        List<Future<?>> list = (List<Future<?>>) f.get(runtime);
        list.clear();
        list.addAll(futures);
    }

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with ACTIVE status, IDLE playing status, zero failures")
        void shouldInitializeFreshState() {
            BotGroupRuntime runtime = new BotGroupRuntime("group-1", 5, "env-1");

            assertThat(runtime.getActualStatus()).isEqualTo(BotGroupStatus.ACTIVE);
            assertThat(runtime.getPlayingStatus()).isEqualTo(BotGroupPlayingStatus.IDLE);
            assertThat(runtime.getConsecutiveFailures()).isEqualTo(0);
            assertThat(runtime.getBotInstances()).isEmpty();
            assertThat(runtime.getBotFutures()).isEmpty();
            assertThat(runtime.getStartedAt()).isNotNull();
            assertThat(runtime.getGroupId()).isEqualTo("group-1");
            assertThat(runtime.getEnvironmentId()).isEqualTo("env-1");

            runtime.getExecutor().shutdownNow();
        }
    }

    @Nested
    @DisplayName("getNextBotForLogout")
    class GetNextBotForLogoutTests {

        @Test
        @DisplayName("Should return null when no bots are present")
        void shouldReturnNullWhenEmpty() {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 0, "env");
            try {
                assertThat(runtime.getNextBotForLogout()).isNull();
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should always return the only bot when there is one")
        void shouldReturnSingleBot() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 1, "env");
            try {
                Bot only = mock(Bot.class);
                setBotInstances(runtime, List.of(only));

                assertThat(runtime.getNextBotForLogout()).isSameAs(only);
                assertThat(runtime.getNextBotForLogout()).isSameAs(only);
                assertThat(runtime.getNextBotForLogout()).isSameAs(only);
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should cycle through bots round-robin (3 bots, 7 calls -> 0,1,2,0,1,2,0)")
        void shouldCycleRoundRobin() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 3, "env");
            try {
                Bot b0 = mock(Bot.class);
                Bot b1 = mock(Bot.class);
                Bot b2 = mock(Bot.class);
                setBotInstances(runtime, List.of(b0, b1, b2));

                Bot[] expectedOrder = {b0, b1, b2, b0, b1, b2, b0};

                for (int i = 0; i < expectedOrder.length; i++) {
                    Bot actual = runtime.getNextBotForLogout();
                    assertThat(actual)
                            .as("call %d", i + 1)
                            .isSameAs(expectedOrder[i]);
                }
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("markAsDead / isGroupDead")
    class DeadStateTests {

        @Test
        @DisplayName("Should mark group as DEAD and report isGroupDead true")
        void shouldMarkAsDead() {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 1, "env");
            try {
                assertThat(runtime.isGroupDead()).isFalse();

                runtime.markAsDead();

                assertThat(runtime.isGroupDead()).isTrue();
                assertThat(runtime.getActualStatus()).isEqualTo(BotGroupStatus.DEAD);
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("stopAllBots")
    class StopAllBotsTests {

        @Test
        @DisplayName("Should call cleanup() on every bot")
        void shouldCleanupAllBots() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 3, "env");
            Bot b1 = mock(Bot.class);
            Bot b2 = mock(Bot.class);
            Bot b3 = mock(Bot.class);
            setBotInstances(runtime, List.of(b1, b2, b3));

            runtime.stopAllBots();

            verify(b1).cleanup();
            verify(b2).cleanup();
            verify(b3).cleanup();
        }

        @Test
        @DisplayName("Should shut down the executor")
        void shouldShutDownExecutor() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 1, "env");
            setBotInstances(runtime, List.of(mock(Bot.class)));

            assertThat(runtime.getExecutor().isShutdown()).isFalse();

            runtime.stopAllBots();

            assertThat(runtime.getExecutor().isShutdown()).isTrue();
        }

        @Test
        @DisplayName("Should continue cleaning up remaining bots even if one cleanup() throws")
        void shouldContinueOnCleanupError() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 3, "env");
            Bot good1 = mock(Bot.class);
            Bot bad = mock(Bot.class);
            Bot good2 = mock(Bot.class);
            doThrow(new RuntimeException("boom")).when(bad).cleanup();
            setBotInstances(runtime, List.of(good1, bad, good2));

            // Should not propagate the exception
            runtime.stopAllBots();

            verify(good1).cleanup();
            verify(bad).cleanup();
            verify(good2).cleanup();
            assertThat(runtime.getExecutor().isShutdown()).isTrue();
        }

        @Test
        @DisplayName("Should shutdown health monitor and logout scheduler if present")
        void shouldShutdownAuxiliarySchedulers() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 0, "env");
            java.util.concurrent.ScheduledExecutorService monitor =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            java.util.concurrent.ScheduledExecutorService logout =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            runtime.setHealthMonitor(monitor);
            runtime.setLogoutScheduler(logout);

            runtime.stopAllBots();

            assertThat(monitor.isShutdown()).isTrue();
            assertThat(logout.isShutdown()).isTrue();
        }
    }

    @Nested
    @DisplayName("getRunningBotCount")
    class GetRunningBotCountTests {

        @Test
        @DisplayName("Should return 0 when no bots have been started")
        void shouldReturnZeroWhenEmpty() {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 0, "env");
            try {
                assertThat(runtime.getRunningBotCount()).isEqualTo(0L);
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should count only futures that are not done")
        void shouldCountNonDoneFutures() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 3, "env");
            try {
                Future<?> done1 = mock(Future.class);
                Future<?> done2 = mock(Future.class);
                Future<?> running1 = mock(Future.class);
                Future<?> running2 = mock(Future.class);
                when(done1.isDone()).thenReturn(true);
                when(done2.isDone()).thenReturn(true);
                when(running1.isDone()).thenReturn(false);
                when(running2.isDone()).thenReturn(false);

                setBotFutures(runtime, List.of(done1, running1, done2, running2));

                assertThat(runtime.getRunningBotCount()).isEqualTo(2L);
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }

        @Test
        @DisplayName("Should return total when all futures are still running")
        void shouldReturnAllWhenAllRunning() throws Exception {
            BotGroupRuntime runtime = new BotGroupRuntime("g", 3, "env");
            try {
                Future<?> f1 = mock(Future.class);
                Future<?> f2 = mock(Future.class);
                Future<?> f3 = mock(Future.class);
                when(f1.isDone()).thenReturn(false);
                when(f2.isDone()).thenReturn(false);
                when(f3.isDone()).thenReturn(false);

                setBotFutures(runtime, List.of(f1, f2, f3));

                assertThat(runtime.getRunningBotCount()).isEqualTo(3L);
            } finally {
                runtime.getExecutor().shutdownNow();
            }
        }
    }
}
