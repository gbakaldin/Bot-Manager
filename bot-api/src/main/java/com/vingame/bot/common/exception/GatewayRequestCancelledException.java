package com.vingame.bot.common.exception;

/**
 * A queued gateway request was called off before it was admitted — the group was stopped,
 * or the start it belonged to was cancelled (GATEWAY_REQUEST_BUDGET AD-8).
 * <p>
 * <b>This never reaches REST.</b> It is thrown only into a start that something else has
 * already decided to abandon, so the caller that sees it is a per-bot task whose result is
 * discarded. It exists as a distinct type so those tasks can tell "we gave up on purpose"
 * apart from "the budget ran out" — the first is not a failure and must not be counted as
 * one.
 * <p>
 * A cancelled waiter is <b>not stamped into the window</b>: it never left the JVM.
 */
public class GatewayRequestCancelledException extends GatewayBudgetException {

    /** Body {@code type} for symmetry with its siblings; no REST path renders it. */
    public static final String TYPE = "Gateway request cancelled";

    public GatewayRequestCancelledException(String environmentId, String scope) {
        super(environmentId, "Gateway request cancelled before admission for " + scope
                + " on environment " + environmentId);
    }

    @Override
    public String getType() {
        return TYPE;
    }
}
