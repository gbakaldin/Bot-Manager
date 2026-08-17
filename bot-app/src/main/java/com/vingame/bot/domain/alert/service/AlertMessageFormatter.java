package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertRegister;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link Alert} into the plain-text body VipTalk's {@code sendMessage}
 * takes. There is no subject field on the wire, so the headline has to be part of
 * the text — that is what this class exists to standardise.
 * <p>
 * Two registers (VIPTALK_ALERTING_V2 AD-V6). {@link AlertRegister#TECHNICAL}, for
 * operators:
 * <pre>
 * 🔴 CRITICAL · TIP (116) · staging
 * Bot group DEAD
 * Group tptxg2 — 18/18 bots dead since 10:04
 * — via prometheus
 * </pre>
 * {@link AlertRegister#CUSTOMER}, for a product room:
 * <pre>
 * ⚠️ TIP (116) · prod
 * ALERT! Bot Management application is experiencing issues, backend team is aware and will deliver fixes soon.
 * </pre>
 * The customer text is <b>not</b> derived from the technical one — it is the rule's
 * {@code public_summary} verbatim, with no severity word, no bullet list, no source
 * trailer, no environment id and no metric values. Anything else risks leaking a
 * stack-trace-shaped summary into a customer-facing room.
 * <p>
 * The header line carries the instance label (VIPTALK_ALERTING AD-7): prod, loadtest
 * and staging run the same artifact and would otherwise be indistinguishable in a
 * shared room. Blank label ⇒ the segment is omitted rather than rendered empty.
 */
@Component
public class AlertMessageFormatter {

    private static final String SEP = " · ";

    private final String instanceLabel;

    public AlertMessageFormatter(@Value("${viptalk.instance-label:}") String instanceLabel) {
        this.instanceLabel = instanceLabel == null ? "" : instanceLabel.strip();
    }

    /**
     * Renders in the technical register — the default for every path that has not
     * explicitly asked for the customer voice.
     *
     * @param alert the alert to render.
     * @return the message text; never blank.
     */
    public String format(Alert alert) {
        return format(alert, AlertRegister.TECHNICAL);
    }

    /**
     * @param alert    the alert to render.
     * @param register which voice to render it in; {@code null} ⇒ technical.
     * @return the message text. Never blank in the technical register. Blank in the
     *         customer register when the alert carries no {@code public_summary} —
     *         fail-closed per AD-V6, and {@code VipTalkClient} skips an empty body.
     *         {@code AlertRouter} gates on {@link Alert#hasPublicSummary()} before
     *         asking for this, so it is a backstop, not a live path.
     */
    public String format(Alert alert, AlertRegister register) {
        if (register == AlertRegister.CUSTOMER) {
            return formatCustomer(alert);
        }

        StringBuilder header = new StringBuilder()
                .append(alert.severity().getMarker())
                .append(' ')
                .append(alert.severity().getLabel());

        for (String segment : contextSegments(alert)) {
            header.append(SEP).append(segment);
        }

        StringBuilder message = new StringBuilder(header).append('\n').append(alert.title());

        if (alert.body() != null && !alert.body().isBlank()) {
            message.append('\n').append(alert.body().strip());
        }
        if (alert.source() != null && !alert.source().isBlank()) {
            message.append("\n— via ").append(alert.source().strip());
        }
        return message.toString();
    }

    /**
     * Marker + product + instance, then the public summary verbatim. Deliberately drops
     * the severity word, the title, the body and the source trailer: all four are written
     * for operators and all four are ways internal wording reaches a product room.
     */
    private String formatCustomer(Alert alert) {
        if (!alert.hasPublicSummary()) {
            return "";
        }
        // The marker glues onto the first context segment with a space (no separator):
        // "⚠️ TIP (116) · prod". The severity word is what is dropped here, not the icon —
        // a product room still gets a visual sense of how bad it is.
        StringBuilder header = new StringBuilder().append(alert.severity().getMarker());
        List<String> segments = contextSegments(alert);
        if (!segments.isEmpty()) {
            header.append(' ').append(String.join(SEP, segments));
        }
        return header.append('\n').append(alert.publicSummary().strip()).toString();
    }

    /** Product and instance, in header order; either may be absent. */
    private List<String> contextSegments(Alert alert) {
        List<String> segments = new ArrayList<>(2);
        if (alert.product() != null) {
            segments.add(alert.product().getDisplayName() + " (" + alert.product().getCode() + ")");
        }
        if (!instanceLabel.isEmpty()) {
            segments.add(instanceLabel);
        }
        return segments;
    }
}
