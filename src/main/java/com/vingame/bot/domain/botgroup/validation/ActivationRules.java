package com.vingame.bot.domain.botgroup.validation;

import com.vingame.bot.common.exception.BadRequestException;
import com.vingame.bot.domain.botgroup.model.ActivationMode;
import com.vingame.bot.domain.botgroup.model.ActivationWindow;
import com.vingame.bot.domain.botgroup.model.BotGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared activation-config rule body for the timed-activation feature
 * (TIMED_ACTIVATION AD-7). Unlike {@link BettingGridRules}, these rules are
 * game-type-independent, so they are invoked once from
 * {@link BotGroupConfigValidationService#validate(BotGroup)} for every
 * create/update rather than from a per-{@code GameType} validator.
 *
 * <p>Rule set (AD-7): when {@link BotGroup#getActivationMode()} is
 * {@link ActivationMode#SCHEDULED} the group opts into schedule-driven
 * start/stop, so a usable window is mandatory:
 * <ul>
 *   <li>{@code activationWindow} must be non-null;</li>
 *   <li>both {@code from} and {@code to} must be non-null;</li>
 *   <li>{@code from} must differ from {@code to} — a zero-length
 *       {@code from == to} window is ambiguous (zero-length vs all-day) and
 *       rejected. Operators wanting "always up" use {@link ActivationMode#MANUAL_ON}
 *       with a {@code null} window.</li>
 * </ul>
 * Non-scheduled modes ({@link ActivationMode#MANUAL_ON},
 * {@link ActivationMode#MANUAL_OFF}) and {@code null} (legacy, non-timed groups)
 * require no window and always pass.
 *
 * <p>Mirroring {@link BettingGridRules}, every violation is accumulated and a
 * single aggregated {@link BadRequestException} (→ HTTP 400) is thrown; a valid
 * config returns normally.
 */
final class ActivationRules {

    private ActivationRules() {
        // Utility holder for the shared rule body — not instantiable.
    }

    /**
     * Validate the activation configuration of a bot group, throwing a single
     * aggregated {@link BadRequestException} if any rule is violated.
     *
     * @param group the bot group to validate
     */
    static void validate(BotGroup group) {
        if (group.getActivationMode() != ActivationMode.SCHEDULED) {
            // MANUAL_ON / MANUAL_OFF / null (legacy): no window required.
            return;
        }

        List<String> violations = new ArrayList<>();

        ActivationWindow window = group.getActivationWindow();
        if (window == null) {
            violations.add("activationWindow is required when activationMode is SCHEDULED");
        } else {
            if (window.getFrom() == null) {
                violations.add("activationWindow.from is required when activationMode is SCHEDULED");
            }
            if (window.getTo() == null) {
                violations.add("activationWindow.to is required when activationMode is SCHEDULED");
            }
            if (window.getFrom() != null && window.getTo() != null
                    && window.getFrom().equals(window.getTo())) {
                violations.add("activationWindow.from (" + window.getFrom()
                        + ") must differ from activationWindow.to (" + window.getTo()
                        + "); a zero-length window is ambiguous — use MANUAL_ON for always-on");
            }
        }

        if (!violations.isEmpty()) {
            throw new BadRequestException(
                    "Invalid bot-group activation config: " + String.join("; ", violations));
        }
    }
}
