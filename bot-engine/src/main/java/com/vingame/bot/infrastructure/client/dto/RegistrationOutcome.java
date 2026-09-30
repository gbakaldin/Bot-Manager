package com.vingame.bot.infrastructure.client.dto;

/**
 * What one call to {@code ApiGatewayClient.registerOne} did, as far as the gateway's answer says
 * (GATEWAY_REQUEST_BUDGET A28.3).
 *
 * <p><b>Both values are a success for the caller</b>, and the distinction exists only so the
 * {@code RegistrationWorker} can count them apart: {@code registration_accounts_total} carries
 * {@code outcome="success"} for {@link #CREATED} and {@code outcome="exists"} for
 * {@link #ALREADY_EXISTED}. The failure case is not a value here — it is an exception, because a
 * failure must cost the index an attempt and a value would let a caller forget to check.
 *
 * <p><b>Classified on the response body, never the HTTP status.</b> gwms answers
 * {@code {"status":"EXISTED","code":409,…}} at <b>HTTP 200</b>, exactly as it answers
 * {@code {"status":"OK","code":200,…}} — captured live on 097/BOM staging, see
 * {@code docs/reviews/GATEWAY_REQUEST_BUDGET/gwms-register-envelope.md}. Reading the HTTP status
 * would classify every re-registration as a fresh account.
 *
 * <p><b>And scoped to the endpoint that was called.</b> {@code update-fullname.aspx} returns the
 * same {@code EXISTED} for a taken <em>display name</em>, which means "re-roll the name", not
 * "this account is done" — {@code ApiGatewayClient.isDisplayNameTaken} is where that reading
 * lives. One status string, two meanings, one per endpoint.
 */
public enum RegistrationOutcome {

    /** The account did not exist and now does. {@code status: "OK"}. */
    CREATED,

    /**
     * The account already existed, so this index is already done — {@code status: "EXISTED"}.
     * <p>
     * This is what makes the worker resumable at all (A2.2): a JVM restart mid-registration
     * re-attempts the index it was on, and without this reading that retry would look like a
     * failure and stop the group.
     * <p>
     * <b>A re-register returns no tokens</b> — no {@code session_id}, no {@code token}, no
     * {@code token2}. Nothing in this codebase needs them (a bot logs in for itself at group
     * start, and {@code update-fullname} authenticates with the per-environment admin
     * {@code X-TOKEN}), which is why a resumed index still costs two requests and not three.
     */
    ALREADY_EXISTED
}
