package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.brand.model.ProductCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves which VipTalk rooms a message goes to.
 * <p>
 * Product rooms come from {@link ProductCode#getVipTalkRoomId()} — hardcoded, one per
 * product (VIPTALK_ALERTING AD-1). The only configured room is the <b>ops room</b>
 * ({@code viptalk.ops-room-id}), which takes fundamental infrastructure alerts (host
 * CPU / RAM / storage, app down or restarted) — <b>not</b> a catch-all any more
 * (VIPTALK_ALERTING_V2 AD-V3). It is configured rather than hardcoded because it is
 * not a property of any product and may legitimately differ per deployment.
 * <p>
 * Which room a given alert reaches is {@link AlertRouter}'s decision, not this class's;
 * this is only the lookup table.
 * <p>
 * Products without a room are skipped silently — {@code null} means "not wired yet",
 * not "misconfigured".
 */
@Slf4j
@Component
public class AlertRoomRegistry {

    private final String opsRoomId;

    public AlertRoomRegistry(@Value("${viptalk.ops-room-id:}") String opsRoomId) {
        this.opsRoomId = opsRoomId == null ? "" : opsRoomId.strip();

        List<ProductCode> wired = new ArrayList<>();
        List<ProductCode> collisions = new ArrayList<>();
        for (ProductCode product : ProductCode.values()) {
            if (product.hasVipTalkRoom()) {
                wired.add(product);
                if (product.getVipTalkRoomId().equals(this.opsRoomId)) {
                    collisions.add(product);
                }
            }
        }
        log.info("VipTalk rooms: {} product room(s) wired {}, ops room {}",
                wired.size(), wired, this.opsRoomId.isEmpty() ? "not configured" : "configured");
        if (!collisions.isEmpty()) {
            // Not fatal, but lossy in a way that is otherwise invisible: an `audience: both`
            // alert renders two registers for two rooms, and if those rooms are the same
            // room the customer notice is deduplicated away — the product room then only
            // ever sees operator wording. Say it once, loudly, at startup.
            log.warn("viptalk.ops-room-id is also the product room of {} — `audience: both` "
                            + "alerts for {} will publish the technical copy only, and the "
                            + "customer notice will be suppressed as a duplicate. Give the ops "
                            + "room its own room ID.",
                    collisions, collisions);
        }
    }

    /**
     * The room for a product, or empty when that product has no room wired.
     */
    public Optional<String> roomFor(ProductCode product) {
        if (product == null || !product.hasVipTalkRoom()) {
            return Optional.empty();
        }
        return Optional.of(product.getVipTalkRoomId());
    }

    /**
     * The ops room, or empty when {@code viptalk.ops-room-id} is unset. Not a catch-all
     * since AD-V3 — see the class javadoc.
     */
    public Optional<String> opsRoom() {
        return opsRoomId.isEmpty() ? Optional.empty() : Optional.of(opsRoomId);
    }

    /**
     * Every room a fleet-wide announcement should reach: all wired product rooms plus
     * the ops room. Deduplicated and order-stable (products in enum order, ops last)
     * so a room configured both as a product room and as the ops room is not sent to twice.
     */
    public List<String> broadcastRooms() {
        LinkedHashSet<String> rooms = new LinkedHashSet<>();
        for (ProductCode product : ProductCode.values()) {
            if (product.hasVipTalkRoom()) {
                rooms.add(product.getVipTalkRoomId());
            }
        }
        opsRoom().ifPresent(rooms::add);
        return List.copyOf(rooms);
    }

    /**
     * Which products are wired, for the operator-facing introspection endpoint.
     * Room IDs are deliberately <b>not</b> returned — {@code /api/v1/alerts/**} is
     * unauthenticated, like the rest of the public API surface.
     */
    public Map<ProductCode, Boolean> coverage() {
        Map<ProductCode, Boolean> coverage = new EnumMap<>(ProductCode.class);
        for (ProductCode product : ProductCode.values()) {
            coverage.put(product, product.hasVipTalkRoom());
        }
        return coverage;
    }
}
