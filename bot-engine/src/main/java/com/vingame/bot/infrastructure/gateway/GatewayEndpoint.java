package com.vingame.bot.infrastructure.gateway;

/**
 * Which kind of gateway request met a Cloudflare edge block — the bounded {@code endpoint} label
 * of {@code gateway_edge_blocks_total} and the word in the circuit's ERROR line
 * (GATEWAY_REQUEST_BUDGET AD-13, Implementation Note 14).
 * <p>
 * <b>A bounded enum and never a URL.</b> A URL carries a host and, for {@code verifytoken.aspx},
 * a bot's session token in its query string; as a label value it would be both unbounded and a
 * credential leak. The six request kinds are AD-3's inventory; {@link #CIRCUIT_PROBE} is the
 * seventh, because the clearance probe is the one request that is <em>expected</em> to meet a
 * block, and counting it under {@link #VERIFY_TOKEN} would make an hourly probe against a
 * standing block read as bots still reading balances.
 */
public enum GatewayEndpoint {

    LOGIN("login"),
    REGISTER("register"),
    UPDATE_FULLNAME("update-fullname"),
    VERIFY_TOKEN("verifytoken"),
    DEPOSIT("deposit"),
    WS_UPGRADE("ws-upgrade"),
    CIRCUIT_PROBE("circuit-probe");

    private final String tag;

    GatewayEndpoint(String tag) {
        this.tag = tag;
    }

    /** The metric label value and the word the ERROR line uses, e.g. {@code login}. */
    public String tag() {
        return tag;
    }
}
