package com.vingame.bot.domain.bot.message;

import com.vingame.bot.domain.game.model.GameType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a message-types provider — a {@link GameMessageTypes},
 * {@link SlotMessageTypes} or {@link TaiXiuMessageTypes} implementation — as the
 * canonical provider for a game type and a set of <b>product code strings</b>.
 * Discovered at startup by {@code MessageTypesRegistry}, which is what replaced
 * {@code GameMessageTypesResolver}'s hardcoded {@code switch (productCode)}
 * (PLUGIN_HOT_RELOAD Phase 2c, AD-17).
 *
 * <p><b>The key is a {@code String}, not a {@code ProductCode}</b> (AD-16).
 * {@code ProductCode} stays an enum — it carries {@code appId},
 * {@code usernameMaxLength} and {@code vipTalkRoomId}, i.e. auth/alerting/validation
 * metadata rather than a plugin key. It is only the <em>message layer</em> that keys
 * on the string {@code ProductCode.getCode()} ({@code "116"}, {@code "097"}), so that
 * shipping a provider for one of the already-declared-but-unimplemented products
 * ({@code 066}, {@code 103}, {@code 105}, {@code 119}, {@code 222}) is a pure
 * addition: a new annotated {@code @Component} and nothing else. Adding an eleventh
 * <em>brand</em> still needs a {@code ProductCode} constant, and therefore an engine
 * release — recorded as an Open Item on the plan, not silently solved.
 *
 * <p><b>{@link #products()} is a list because a provider may serve several
 * products.</b> {@code BomGameMessageTypes} legitimately claims {@code "097"} and
 * {@code "098"} today; a single-valued member would silently have dropped one of
 * them.
 *
 * <p><b>An empty {@link #products()} means product-neutral</b> and is only valid for
 * {@link GameType#SLOT}: slot message classes, CMDs and protocol are identical across
 * every brand (SLOT_MACHINE_BOT AD-3/AD-4), which is why {@code MessageTypesRegistry.slot()}
 * takes no product argument at all.
 *
 * <p>The {@code switch}'s compile-time exhaustiveness is replaced by
 * {@code MessageTypesCoverageTest} (AD-19), which asserts that every
 * {@code ProductCode} either resolves a provider or is on an explicit
 * "not yet implemented" inventory.
 *
 * <p>See {@code docs/plans/PLUGIN_HOT_RELOAD.md} AD-16 through AD-20.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MessageTypesImpl {

    /**
     * Which game type this provider serves. Cross-checked at registration against
     * the contract interface the bean was discovered under, so a
     * {@code GameMessageTypes} annotated {@code gameType = SLOT} fails the context
     * refresh rather than registering into the wrong table.
     */
    GameType gameType();

    /**
     * The product code strings this provider claims, as
     * {@code ProductCode.getCode()} spells them ({@code "097"}, not {@code "P_097"}).
     * Empty means product-neutral, which only {@link GameType#SLOT} may be.
     */
    String[] products() default {};
}
