package com.vingame.bot.domain.alert.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AD-V4: an absent, blank or unrecognised {@code audience} label resolves to
 * {@link AlertAudience#INTERNAL} — the one room guaranteed to exist — so a rule can never
 * silently vanish. A typo in a rule label is the case that matters here: {@code audiance:
 * product} must still deliver somewhere.
 */
class AlertAudienceTest {

    @Test
    void parsesTheThreeDeclaredValuesCaseInsensitively() {
        assertEquals(AlertAudience.INTERNAL, AlertAudience.fromLabel("internal"));
        assertEquals(AlertAudience.PRODUCT, AlertAudience.fromLabel("Product"));
        assertEquals(AlertAudience.BOTH, AlertAudience.fromLabel("  BOTH "));
    }

    @Test
    void defaultsToInternalForNullBlankAndUnknown() {
        assertEquals(AlertAudience.INTERNAL, AlertAudience.fromLabel(null));
        assertEquals(AlertAudience.INTERNAL, AlertAudience.fromLabel("   "));
        assertEquals(AlertAudience.INTERNAL, AlertAudience.fromLabel("customer"));
    }
}
