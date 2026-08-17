package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertDispatch;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.infrastructure.notification.VipTalkClient;
import com.vingame.bot.infrastructure.notification.VipTalkSendResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delivery behaviour of {@link AlertService}: which rooms a message reaches, how many
 * POSTs that costs, what happens when there are no rooms, and what
 * {@code alert_dispatch_total} records (VIPTALK_ALERTING_V2 Phase 2).
 * <p>
 * The routing decisions themselves are pinned in {@link AlertRouterTest}; this suite
 * covers the service's own responsibilities — grouping by text, transport outcomes and
 * the counter.
 * <p>
 * Misroute routing is asserted against a product with <em>no</em> hardcoded room
 * (P_097/BOM), and product routing against whatever {@link AlertRoomRegistry} reports for
 * the product rather than a literal room ID — so filling in more rooms on
 * {@link ProductCode} never breaks this suite. Pick a roomless product here, not a wired
 * one, if you add a misroute case.
 */
class AlertServiceTest {

    private static final String OPS_ROOM = "!ops:matrix-uat.viptalk.org";

    private MeterRegistry meters;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
    }

    private AlertService serviceWith(String opsRoom, VipTalkClient client) {
        return serviceWith(opsRoom, client, false);
    }

    private AlertService serviceWith(String opsRoom, VipTalkClient client, boolean customerNotices) {
        AlertRoomRegistry registry = new AlertRoomRegistry(opsRoom);
        AlertMessageFormatter formatter = new AlertMessageFormatter("staging");
        return new AlertService(client, registry, formatter,
                new AlertRouter(registry, formatter, customerNotices), meters);
    }

    private VipTalkClient acceptingClient(AtomicReference<List<String>> capturedRooms,
                                          AtomicReference<String> capturedText) {
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.isEnabled()).thenReturn(true);
        when(client.send(anyString(), anyList())).thenAnswer(invocation -> {
            capturedText.set(invocation.getArgument(0));
            capturedRooms.set(invocation.getArgument(1));
            return VipTalkSendResult.sent(invocation.<List<String>>getArgument(1).size(), 200);
        });
        return client;
    }

    private double dispatchCount(String outcome, String reason, String product) {
        var counter = meters.find(AlertRouter.ALERT_DISPATCH_TOTAL)
                .tag("outcome", outcome).tag("reason", reason).tag("product", product).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    void send_misroutesProductWithoutRoomToOpsRoomTagged() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AlertService service = serviceWith(OPS_ROOM, acceptingClient(rooms, text));

        AlertDispatch dispatch = service.send(Alert.forProduct(
                ProductCode.P_097, AlertSeverity.CRITICAL, "Bot group DEAD", "18/18 bots dead", "prometheus"));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        // AD-V5 as amended: delivered to ops rather than dropped, because 9 of 10 products
        // have no room yet and silently discarding their alerts is the worse failure.
        assertEquals(List.of(OPS_ROOM), rooms.get());
        assertTrue(text.get().startsWith("↪️ MISROUTED"), text.get());
        assertTrue(text.get().contains("BOM (097)"), text.get());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_MISROUTED, AlertRouter.REASON_NO_ROOM, "097"));
    }

    @Test
    void send_misrouteWithoutAnyProductIsTaggedNoProduct() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AlertService service = serviceWith(OPS_ROOM, acceptingClient(rooms, text));

        service.send(new Alert(AlertSeverity.WARNING, "Unroutable", null, null, "prometheus",
                AlertAudience.PRODUCT, null));

        assertEquals(List.of(OPS_ROOM), rooms.get());
        assertTrue(text.get().contains("no resolvable product"), text.get());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_MISROUTED, AlertRouter.REASON_NO_PRODUCT, "none"));
    }

    @Test
    void send_routesProductWithItsOwnRoomThereNotToOps() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AlertService service = serviceWith(OPS_ROOM, acceptingClient(rooms, text));
        // Expectation comes from the registry, not a literal ID, so re-pointing the room
        // in ProductCode does not break this test.
        String productRoom = new AlertRoomRegistry(OPS_ROOM).roomFor(ProductCode.P_116).orElseThrow();

        AlertDispatch dispatch = service.send(new Alert(AlertSeverity.CRITICAL, "Bot group DEAD",
                "18/18 bots dead", ProductCode.P_116, "prometheus", AlertAudience.PRODUCT, null));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        assertEquals(List.of(productRoom), rooms.get());
        assertFalse(rooms.get().contains(OPS_ROOM), "B2: a product alert must not reach the ops room");
        assertTrue(text.get().contains("TIP (116)"), text.get());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK, "116"));
    }

    @Test
    void send_internalAudienceGoesToOpsRoomOnly() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        AlertService service = serviceWith(OPS_ROOM, acceptingClient(rooms, text));

        service.send(new Alert(AlertSeverity.CRITICAL, "HostDiskSpaceLow", "12% free",
                ProductCode.P_116, "prometheus", AlertAudience.INTERNAL, null));

        // Even with a product on the alert, an internal audience never reaches its room.
        assertEquals(List.of(OPS_ROOM), rooms.get());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK, "116"));
    }

    @Test
    void send_bothAudienceSendsTwoDistinctTextsToTwoRooms() {
        List<List<String>> roomSets = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList())).thenAnswer(invocation -> {
            texts.add(invocation.getArgument(0));
            roomSets.add(invocation.getArgument(1));
            return VipTalkSendResult.sent(invocation.<List<String>>getArgument(1).size(), 200);
        });
        AlertService service = serviceWith(OPS_ROOM, client, true);
        String productRoom = new AlertRoomRegistry(OPS_ROOM).roomFor(ProductCode.P_116).orElseThrow();

        AlertDispatch dispatch = service.send(new Alert(AlertSeverity.CRITICAL, "BotManagerDown",
                "Prometheus has failed to scrape bot-manager for 2 minutes", ProductCode.P_116,
                "prometheus", AlertAudience.BOTH, "ALERT! Bot Management application is experiencing issues."));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        assertEquals(List.of(List.of(OPS_ROOM), List.of(productRoom)), roomSets,
                "two rooms, two registers — technical to ops, customer to the product room");
        assertTrue(texts.get(0).contains("Prometheus has failed to scrape"), texts.get(0));
        assertTrue(texts.get(1).contains("ALERT! Bot Management application"), texts.get(1));
        assertFalse(texts.get(1).contains("Prometheus has failed to scrape"),
                "operator detail must not leak into the customer register");
        assertEquals(2d, dispatchCount(AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK, "116"));
    }

    @Test
    void send_bothAudienceWithUnwiredProductProducesExactlyOneOpsMessage() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        VipTalkClient client = acceptingClient(rooms, text);
        AlertService service = serviceWith(OPS_ROOM, client, true);

        AlertDispatch dispatch = service.send(new Alert(AlertSeverity.CRITICAL, "BotManagerDown",
                "scrape failed", ProductCode.P_097, "prometheus", AlertAudience.BOTH,
                "ALERT! Bot Management application is experiencing issues."));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        // AD-V5: the misrouted customer register must not add a SECOND ops message on top
        // of the technical one — dedupe on (alert, room).
        verify(client).send(anyString(), anyList());
        assertEquals(List.of(OPS_ROOM), rooms.get());
        assertFalse(text.get().contains("ALERT! Bot Management"),
                "the ops room gets the technical copy, not the customer one");
        // The suppressed copy is still counted, so the misroute is visible in the metric.
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_MISROUTED, AlertRouter.REASON_NO_ROOM, "097"));
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK, "097"));
    }

    @Test
    void send_withNoRoomAndNoOpsRoomSkipsInsteadOfFailing() {
        VipTalkClient client = mock(VipTalkClient.class);
        AlertService service = serviceWith("", client);

        AlertDispatch dispatch = service.send(Alert.forProduct(
                ProductCode.P_097, AlertSeverity.WARNING, "something", null, null));

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, dispatch.outcome());
        verify(client, never()).send(any(), any());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_DROPPED, AlertRouter.REASON_NO_ROOM, "097"));
    }

    @Test
    void send_internalWithNoOpsRoomIsDroppedAndCounted() {
        VipTalkClient client = mock(VipTalkClient.class);
        AlertService service = serviceWith("", client);

        AlertDispatch dispatch = service.send(new Alert(AlertSeverity.CRITICAL, "JvmThreadsHigh",
                null, null, "prometheus", AlertAudience.INTERNAL, null));

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, dispatch.outcome());
        verify(client, never()).send(any(), any());
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_DROPPED, AlertRouter.REASON_NO_OPS_ROOM, "none"));
    }

    @Test
    void send_disabledChannelIsCountedAsDroppedNotSent() {
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList()))
                .thenReturn(VipTalkSendResult.skipped("VipTalk channel is disabled"));
        AlertService service = serviceWith(OPS_ROOM, client);

        service.send(new Alert(AlertSeverity.WARNING, "probe", null, ProductCode.P_116,
                "prometheus", AlertAudience.INTERNAL, null));

        assertEquals(0d, dispatchCount(AlertRouter.OUTCOME_SENT, AlertRouter.REASON_OK, "116"));
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_DROPPED, AlertRouter.REASON_CHANNEL_DISABLED, "116"));
    }

    @Test
    void broadcast_addressesEveryWiredRoomInOneSend() {
        AtomicReference<List<String>> rooms = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>();
        VipTalkClient client = acceptingClient(rooms, text);
        AlertService service = serviceWith(OPS_ROOM, client);

        AlertDispatch dispatch = service.broadcast(Alert.announcement(
                AlertSeverity.INFO, "Maintenance 22:00-23:00", "Groups will be stopped.", "operator"));

        assertEquals(VipTalkSendResult.Outcome.SENT, dispatch.outcome());
        // AD-4: one request, not one per room.
        verify(client).send(anyString(), anyList());
        assertEquals(new java.util.HashSet<>(rooms.get()).size(), rooms.get().size(), "rooms are deduplicated");
        assertTrue(rooms.get().contains(OPS_ROOM));
    }

    @Test
    void broadcast_withNoRoomsWiredSkips() {
        VipTalkClient client = mock(VipTalkClient.class);
        // The registry is stubbed rather than built from a blank ops room: now that at
        // least one ProductCode carries a room, a real registry can never report zero
        // broadcast targets. The guard in AlertService still has to hold for the day
        // every room is un-wired, so it is pinned directly.
        AlertRoomRegistry emptyRegistry = mock(AlertRoomRegistry.class);
        when(emptyRegistry.broadcastRooms()).thenReturn(List.of());
        AlertMessageFormatter formatter = new AlertMessageFormatter("staging");
        AlertService service = new AlertService(client, emptyRegistry, formatter,
                new AlertRouter(emptyRegistry, formatter, false), meters);

        AlertDispatch dispatch = service.broadcast(
                Alert.announcement(AlertSeverity.INFO, "hello", null, null));

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, dispatch.outcome());
        verify(client, never()).send(any(), any());
    }

    @Test
    void send_transportFailurePropagatesAsFailedDispatch() {
        VipTalkClient client = mock(VipTalkClient.class);
        when(client.send(anyString(), anyList()))
                .thenReturn(VipTalkSendResult.failed(1, 502, "VipTalk returned HTTP 502"));
        AlertService service = serviceWith(OPS_ROOM, client);

        AlertDispatch dispatch = service.send(
                Alert.announcement(AlertSeverity.CRITICAL, "boom", null, null));

        assertTrue(dispatch.isFailed());
        assertTrue(dispatch.detail().contains("502"));
        assertEquals(1d, dispatchCount(AlertRouter.OUTCOME_FAILED, AlertRouter.REASON_NO_PRODUCT, "none"));
    }

    @Test
    void send_neverThrowsOnANullAlert() {
        VipTalkClient client = mock(VipTalkClient.class);
        AlertService service = serviceWith(OPS_ROOM, client);

        assertEquals(VipTalkSendResult.Outcome.SKIPPED, service.send(null).outcome());
        verify(client, never()).send(any(), any());
    }
}
