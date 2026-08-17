package com.vingame.bot.domain.alert.dto;

import com.vingame.bot.domain.alert.model.AlertSeverity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Operator-supplied message for the manual publish endpoints — the maintenance-notice
 * path, as opposed to the machine-generated Alertmanager path.
 *
 * @param title    one-line headline. Required.
 * @param body     optional detail, may be multi-line.
 * @param severity presentation severity; {@code null} ⇒ {@link AlertSeverity#INFO},
 *                 which is the right default for an announcement.
 */
public record AlertRequest(

        @NotBlank
        @Schema(example = "Scheduled maintenance 22:00–23:00 ICT")
        String title,

        @Schema(example = "All bot groups will be stopped for the duration of the window.")
        String body,

        @Schema(example = "INFO")
        AlertSeverity severity) {

    public AlertSeverity severityOrDefault() {
        return severity == null ? AlertSeverity.INFO : severity;
    }
}
