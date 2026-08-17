package com.vingame.bot.domain.alert.dto;

import com.vingame.bot.domain.alert.model.AlertDispatch;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What happened to a publish request.
 *
 * @param outcome   {@code SENT}, {@code SKIPPED} (nothing to do — channel disabled or no
 *                  rooms wired) or {@code FAILED}.
 * @param roomCount how many rooms were addressed.
 * @param detail    human-readable explanation, notably the reason on SKIPPED/FAILED.
 */
public record AlertDispatchDTO(

        @Schema(example = "SENT")
        String outcome,

        @Schema(example = "3")
        int roomCount,

        @Schema(example = "delivered to 3 room(s)")
        String detail) {

    public static AlertDispatchDTO from(AlertDispatch dispatch) {
        return new AlertDispatchDTO(dispatch.outcome().name(), dispatch.roomCount(), dispatch.detail());
    }
}
