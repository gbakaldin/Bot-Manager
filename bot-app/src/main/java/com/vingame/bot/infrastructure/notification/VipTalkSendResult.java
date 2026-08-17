package com.vingame.bot.infrastructure.notification;

/**
 * Outcome of a single {@link VipTalkClient#send} call.
 * <p>
 * The channel never throws (VIPTALK_ALERTING AD-3) — every failure mode, including
 * transport errors and a disabled channel, is expressed as one of these.
 *
 * @param outcome    what happened; see {@link Outcome}.
 * @param roomCount  number of rooms the message was addressed to (0 when skipped).
 * @param statusCode HTTP status returned by VipTalk, or {@code 0} when no request
 *                   was made (skipped, or a transport failure before a response).
 * @param detail     human-readable description, safe to log and to return to an operator.
 */
public record VipTalkSendResult(Outcome outcome, int roomCount, int statusCode, String detail) {

    public enum Outcome {
        /** VipTalk accepted the message (2xx). */
        SENT,
        /** Nothing was sent and nothing is wrong — channel disabled, or no rooms to send to. */
        SKIPPED,
        /** A send was attempted and failed (non-2xx, or transport error). */
        FAILED
    }

    public static VipTalkSendResult sent(int roomCount, int statusCode) {
        return new VipTalkSendResult(Outcome.SENT, roomCount, statusCode,
                "delivered to " + roomCount + " room(s)");
    }

    public static VipTalkSendResult skipped(String detail) {
        return new VipTalkSendResult(Outcome.SKIPPED, 0, 0, detail);
    }

    public static VipTalkSendResult failed(int roomCount, int statusCode, String detail) {
        return new VipTalkSendResult(Outcome.FAILED, roomCount, statusCode, detail);
    }

    public boolean isSent() {
        return outcome == Outcome.SENT;
    }

    public boolean isFailed() {
        return outcome == Outcome.FAILED;
    }
}
