package com.vingame.bot.domain.alert.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Which products have a VipTalk room wired, for operator introspection — the quick
 * answer to "why did nothing arrive for 098?".
 * <p>
 * Room IDs are deliberately omitted: {@code /api/v1/alerts/**} is unauthenticated like
 * the rest of the public API surface, and there is no reason to publish internal chat
 * room identifiers on it.
 *
 * @param enabled            whether VipTalk delivery is active at all
 *                           ({@code viptalk.enabled} plus a non-blank token).
 * @param opsRoomConfigured  whether the catch-all ops room is set.
 * @param broadcastRoomCount how many rooms a broadcast would reach.
 * @param products           per-product wiring state.
 */
public record AlertRoomsDTO(
        boolean enabled,
        boolean opsRoomConfigured,
        int broadcastRoomCount,
        List<ProductRoom> products) {

    /**
     * @param code           numeric product code, e.g. {@code "116"}.
     * @param name           product display name, e.g. {@code "TIP"}.
     * @param roomConfigured whether this product has a room to publish to.
     */
    public record ProductRoom(
            @Schema(example = "116") String code,
            @Schema(example = "TIP") String name,
            boolean roomConfigured) {
    }
}
