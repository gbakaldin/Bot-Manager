package com.vingame.bot.domain.alert.service;

import com.vingame.bot.domain.alert.model.Alert;
import com.vingame.bot.domain.alert.model.AlertRegister;
import com.vingame.bot.domain.alert.model.AlertSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <b>The recovery half is its own copy.</b> A resolving {@code audience: both} alert still
 * carries the rule's {@code public_summary}, so rendering it unchanged would publish
 * "…is experiencing issues…" under a {@code ✅} marker at the exact moment the incident
 * ended. {@link AlertSeverity#RESOLVED} therefore renders the rule's
 * {@code public_resolved_summary}, falling back to {@code viptalk.public-resolved-summary}
 * and, failing that, to nothing at all — never to the firing copy.
 * <p>
 * The header line carries the instance label (VIPTALK_ALERTING AD-7): prod, loadtest
 * and staging run the same artifact and would otherwise be indistinguishable in a
 * shared room. Blank label ⇒ the segment is omitted rather than rendered empty, and the
 * constructor logs a WARN — nothing guesses a value, because a header that says
 * {@code staging} on the prod box is worse than one that says nothing.
 */
@Slf4j
@Component
public class AlertMessageFormatter {

    private static final String SEP = " · ";

    /**
     * Fallback recovery copy for an {@code audience: both} rule that declares a
     * {@code public_summary} but no {@code public_resolved_summary}. Deliberately generic —
     * it has to be true of every customer-facing rule — and deliberately one string in one
     * place: override it with {@code viptalk.public-resolved-summary} /
     * {@code VIPTALK_PUBLIC_RESOLVED_SUMMARY}, no code change needed. A blank override
     * means "use this default", so an empty compose variable cannot silently turn recovery
     * notices off.
     */
    public static final String DEFAULT_PUBLIC_RESOLVED_SUMMARY =
            "UPDATE: the issue reported earlier has been resolved. "
                    + "Everything is operating normally again. Thank you for your patience.";

    private final String instanceLabel;
    private final String defaultResolvedSummary;

    @Autowired
    public AlertMessageFormatter(@Value("${viptalk.instance-label:}") String instanceLabel,
                                 @Value("${viptalk.public-resolved-summary:" + DEFAULT_PUBLIC_RESOLVED_SUMMARY + "}")
                                 String defaultResolvedSummary) {
        this.instanceLabel = instanceLabel == null ? "" : instanceLabel.strip();
        this.defaultResolvedSummary = defaultResolvedSummary == null || defaultResolvedSummary.isBlank()
                ? DEFAULT_PUBLIC_RESOLVED_SUMMARY : defaultResolvedSummary.strip();
        if (this.instanceLabel.isEmpty()) {
            // AD-V15: the instance label is the ONLY thing telling prod, loadtest and
            // staging apart in a shared room. Absent is survivable (the segment is simply
            // omitted, so nothing is claimed); a wrong one is not, which is why nothing
            // defaults it to a guess. Say so loudly at startup instead.
            log.warn("viptalk.instance-label is not set — alert headers will not say which "
                    + "instance produced them. Set VIPTALK_INSTANCE_LABEL (prod / loadtest / staging).");
        }
    }

    /** Test / ad-hoc shorthand: the built-in default recovery copy. */
    AlertMessageFormatter(String instanceLabel) {
        this(instanceLabel, DEFAULT_PUBLIC_RESOLVED_SUMMARY);
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
     *         customer register when {@link #customerSummary(Alert)} has nothing to
     *         publish — fail-closed per AD-V6, and {@code VipTalkClient} skips an empty
     *         body. {@code AlertRouter} gates on the same method before asking for this,
     *         so the blank return is a backstop, not a live path.
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
     * The customer-facing text for this alert, or {@code null} when there is none to render.
     * This is the single place that decides <b>which</b> operator-authored string a product
     * room may see; {@link AlertRouter} gates on it, and {@link #formatCustomer} renders it.
     * <ul>
     *   <li>firing ⇒ the rule's {@code public_summary}, or nothing (AD-V6 fail-closed);</li>
     *   <li>resolved ⇒ the rule's {@code public_resolved_summary}, else the configured
     *       default — but only for a rule that also declared a {@code public_summary}, i.e.
     *       one that is customer-facing in the first place. Announcing a recovery for an
     *       outage the room was never told about is its own kind of confusing, and it is
     *       the case that keeps the fail-closed posture intact when neither annotation
     *       exists.</li>
     * </ul>
     * Nothing here is ever derived from the title, body, severity or source.
     *
     * @param alert the alert; {@code null} ⇒ {@code null}.
     * @return the copy to publish, already stripped, or {@code null} to publish nothing.
     */
    public String customerSummary(Alert alert) {
        if (alert == null) {
            return null;
        }
        if (alert.severity() != AlertSeverity.RESOLVED) {
            return alert.hasPublicSummary() ? alert.publicSummary().strip() : null;
        }
        if (alert.hasPublicResolvedSummary()) {
            return alert.publicResolvedSummary().strip();
        }
        return alert.hasPublicSummary() ? defaultResolvedSummary : null;
    }

    /**
     * Marker + product + instance, then the customer copy verbatim. Deliberately drops
     * the severity word, the title, the body and the source trailer: all four are written
     * for operators and all four are ways internal wording reaches a product room.
     */
    private String formatCustomer(Alert alert) {
        String summary = customerSummary(alert);
        if (summary == null || summary.isBlank()) {
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
        return header.append('\n').append(summary).toString();
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
