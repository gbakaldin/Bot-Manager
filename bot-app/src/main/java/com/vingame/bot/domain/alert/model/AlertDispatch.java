package com.vingame.bot.domain.alert.model;

import com.vingame.bot.infrastructure.notification.VipTalkSendResult;

import java.util.List;

/**
 * Result of publishing one or more alerts, aggregated across every room involved.
 * <p>
 * A dispatch can be partially successful — a broadcast is one request per room-set,
 * but the Alertmanager path fans out one request per product room, and those can
 * disagree. {@link #outcome()} is the worst outcome across all of them: any FAILED
 * ⇒ FAILED, else any SENT ⇒ SENT, else SKIPPED.
 *
 * @param outcome   the aggregate outcome.
 * @param roomCount total rooms addressed across all sends.
 * @param detail    human-readable summary, safe to return to an operator.
 */
public record AlertDispatch(VipTalkSendResult.Outcome outcome, int roomCount, String detail) {

    public static AlertDispatch skipped(String detail) {
        return new AlertDispatch(VipTalkSendResult.Outcome.SKIPPED, 0, detail);
    }

    /** Lifts a single transport result into a dispatch result. */
    public static AlertDispatch of(VipTalkSendResult result) {
        return new AlertDispatch(result.outcome(), result.roomCount(), result.detail());
    }

    /**
     * Aggregates several transport results (the fan-out case).
     *
     * @param results one result per room-set that was sent to; empty ⇒ SKIPPED.
     */
    public static AlertDispatch aggregate(List<VipTalkSendResult> results) {
        if (results == null || results.isEmpty()) {
            return skipped("nothing to send");
        }
        if (results.size() == 1) {
            return of(results.getFirst());
        }

        int rooms = results.stream().mapToInt(VipTalkSendResult::roomCount).sum();
        long failed = results.stream().filter(VipTalkSendResult::isFailed).count();
        long sent = results.stream().filter(VipTalkSendResult::isSent).count();

        if (failed > 0) {
            String reasons = results.stream()
                    .filter(VipTalkSendResult::isFailed)
                    .map(VipTalkSendResult::detail)
                    .distinct()
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("unknown failure");
            return new AlertDispatch(VipTalkSendResult.Outcome.FAILED, rooms,
                    failed + " of " + results.size() + " send(s) failed: " + reasons);
        }
        if (sent > 0) {
            return new AlertDispatch(VipTalkSendResult.Outcome.SENT, rooms,
                    "delivered to " + rooms + " room(s) in " + sent + " send(s)");
        }
        return skipped("all sends skipped");
    }

    public boolean isFailed() {
        return outcome == VipTalkSendResult.Outcome.FAILED;
    }
}
