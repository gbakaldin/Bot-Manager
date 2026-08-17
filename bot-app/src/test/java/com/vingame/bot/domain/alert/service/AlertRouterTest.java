package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertAudience;
import com.vingame.bot.domain.alert.model.AlertRegister;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import com.vingame.bot.domain.alert.service.AlertRouter.RoutedMessage;
import com.vingame.bot.domain.brand.model.ProductCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audience fork itself (VIPTALK_ALERTING_V2 AD-V3/AD-V5/AD-V6/AD-V7), with no Spring
 * context, no mocks and no transport — {@link AlertRouter} is a pure function, and this is
 * where the three user requirements are actually pinned:
 * <ul>
 *   <li>a product room gets its product's alerts, and the ops room does not;</li>
 *   <li>the ops room gets infrastructure only;</li>
 *   <li>a serious outage reaches both, in two different registers.</li>
 * </ul>
 * P_116 is the wired product and P_097 a deliberately un-wired one; both are read through
 * {@link AlertRoomRegistry} rather than as literal IDs, so wiring more rooms later cannot
 * break the suite by itself.
 */
class AlertRouterTest {

    private static final String OPS_ROOM = "!ops:matrix-uat.viptalk.org";
    private static final String PUBLIC_SUMMARY =
            "ALERT! Bot Management application is experiencing issues, backend team is aware "
                    + "and will deliver fixes soon.";

    private AlertRouter router(String opsRoom, boolean customerNotices) {
        AlertRoomRegistry rooms = new AlertRoomRegistry(opsRoom);
        return new AlertRouter(rooms, new AlertMessageFormatter("prod"), customerNotices);
    }

    private String productRoom(ProductCode product) {
        return new AlertRoomRegistry(OPS_ROOM).roomFor(product).orElseThrow();
    }

    private Alert alert(AlertAudience audience, ProductCode product, String publicSummary) {
        return new Alert(AlertSeverity.CRITICAL, "BotManagerDown", "scrape failed for 2m",
                product, "prometheus", audience, publicSummary);
    }

    @Test
    void internalGoesToTheOpsRoomInTheTechnicalRegister() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.INTERNAL, ProductCode.P_116, PUBLIC_SUMMARY));

        assertEquals(1, routed.size());
        assertEquals(OPS_ROOM, routed.getFirst().roomId());
        assertEquals(AlertRegister.TECHNICAL, routed.getFirst().register());
        assertEquals(AlertRouter.OUTCOME_SENT, routed.getFirst().outcome());
        // Even with a wired product and a public summary present: internal means internal.
        assertTrue(routed.getFirst().text().contains("scrape failed"));
    }

    @Test
    void productGoesToItsOwnRoomAndNeverToOps() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.PRODUCT, ProductCode.P_116, null));

        assertEquals(1, routed.size());
        assertEquals(productRoom(ProductCode.P_116), routed.getFirst().roomId());
        assertFalse(routed.getFirst().roomId().equals(OPS_ROOM),
                "requirement B2: product noise must not reach the ops room");
        assertEquals(AlertRouter.REASON_OK, routed.getFirst().reason());
    }

    @Test
    void productWithoutARoomMisroutesToOpsWithAMarker() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.PRODUCT, ProductCode.P_097, null));

        assertEquals(1, routed.size());
        RoutedMessage decision = routed.getFirst();
        assertEquals(OPS_ROOM, decision.roomId());
        assertEquals(AlertRouter.OUTCOME_MISROUTED, decision.outcome());
        assertEquals(AlertRouter.REASON_NO_ROOM, decision.reason());
        // The marker is what lets an ops reader tell this apart from a genuine internal alert.
        assertTrue(decision.text().startsWith("↪️ MISROUTED"), decision.text());
        assertTrue(decision.text().contains("BOM (097)"), decision.text());
    }

    @Test
    void productWithNoResolvableProductMisroutesTaggedNoProduct() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.PRODUCT, null, null));

        assertEquals(AlertRouter.REASON_NO_PRODUCT, routed.getFirst().reason());
        assertEquals(OPS_ROOM, routed.getFirst().roomId());
    }

    @Test
    void productWithoutARoomAndWithoutAnOpsRoomIsDropped() {
        List<RoutedMessage> routed = router("", false).route(
                alert(AlertAudience.PRODUCT, ProductCode.P_097, null));

        assertEquals(1, routed.size());
        assertFalse(routed.getFirst().isDelivered());
        assertEquals(AlertRouter.OUTCOME_DROPPED, routed.getFirst().outcome());
        assertEquals(AlertRouter.REASON_NO_ROOM, routed.getFirst().reason());
    }

    @Test
    void bothSplitsIntoATechnicalOpsCopyAndACustomerProductCopy() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.BOTH, ProductCode.P_116, PUBLIC_SUMMARY));

        assertEquals(2, routed.size());
        RoutedMessage internal = routed.get(0);
        RoutedMessage customer = routed.get(1);

        assertEquals(OPS_ROOM, internal.roomId());
        assertEquals(AlertRegister.TECHNICAL, internal.register());
        assertTrue(internal.text().contains("CRITICAL"), internal.text());
        assertTrue(internal.text().contains("scrape failed"), internal.text());

        assertEquals(productRoom(ProductCode.P_116), customer.roomId());
        assertEquals(AlertRegister.CUSTOMER, customer.register());
        assertEquals("🔴 TIP (116) · prod\n" + PUBLIC_SUMMARY, customer.text());
        assertFalse(customer.text().contains("scrape failed"), "no operator detail in a product room");
        assertFalse(customer.text().contains("prometheus"), "no source trailer in a product room");
    }

    @Test
    void bothDegradesToInternalWhenCustomerNoticesAreDisabled() {
        List<RoutedMessage> routed = router(OPS_ROOM, false).route(
                alert(AlertAudience.BOTH, ProductCode.P_116, PUBLIC_SUMMARY));

        // AD-V7: staging and loadtest run the same artifact into the same rooms, so the
        // customer register is a prod-only privilege.
        assertEquals(1, routed.stream().filter(RoutedMessage::isDelivered).count());
        assertEquals(OPS_ROOM, routed.getFirst().roomId());
        RoutedMessage suppressed = routed.get(1);
        assertFalse(suppressed.isDelivered());
        assertEquals(AlertRouter.REASON_CUSTOMER_NOTICES_DISABLED, suppressed.reason());
    }

    @Test
    void bothWithoutAPublicSummarySuppressesTheCustomerCopyFailClosed() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.BOTH, ProductCode.P_116, "   "));

        assertEquals(1, routed.stream().filter(RoutedMessage::isDelivered).count());
        assertEquals(OPS_ROOM, routed.getFirst().roomId());
        assertEquals(AlertRouter.REASON_NO_PUBLIC_SUMMARY, routed.get(1).reason());
    }

    @Test
    void bothWithAnUnwiredProductNeverProducesTwoOpsMessages() {
        List<RoutedMessage> routed = router(OPS_ROOM, true).route(
                alert(AlertAudience.BOTH, ProductCode.P_097, PUBLIC_SUMMARY));

        // The customer register would misroute to ops (AD-V5), but ops is already receiving
        // the technical copy of the same alert — dedupe on (alert, room) keeps it to one.
        List<RoutedMessage> delivered = routed.stream().filter(RoutedMessage::isDelivered).toList();
        assertEquals(1, delivered.size());
        assertEquals(OPS_ROOM, delivered.getFirst().roomId());
        assertEquals(AlertRegister.TECHNICAL, delivered.getFirst().register());
        // The suppressed copy still reports the misroute, so the AD-V5 rollout metric is honest.
        assertEquals(AlertRouter.OUTCOME_MISROUTED, routed.get(1).outcome());
        assertEquals(AlertRouter.REASON_NO_ROOM, routed.get(1).reason());
    }

    @Test
    void bothWithoutAnOpsRoomStillReachesTheProductRoom() {
        List<RoutedMessage> routed = router("", true).route(
                alert(AlertAudience.BOTH, ProductCode.P_116, PUBLIC_SUMMARY));

        assertEquals(AlertRouter.REASON_NO_OPS_ROOM, routed.getFirst().reason());
        assertEquals(productRoom(ProductCode.P_116), routed.get(1).roomId());
    }

    @Test
    void routerIsTotal_nullAlertAndNullAudienceNeverThrow() {
        AlertRouter router = router(OPS_ROOM, true);

        assertTrue(router.route(null).isEmpty());
        // AD-V4: a null audience is INTERNAL, not an exception and not a silent drop.
        List<RoutedMessage> routed = router.route(new Alert(
                null, "no audience", null, null, null, null, null));
        assertEquals(1, routed.size());
        assertEquals(OPS_ROOM, routed.getFirst().roomId());
    }
}
