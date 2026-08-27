package com.vingame.bot.domain.bot.message.g2.b52;

import com.vingame.bot.domain.bot.message.EndGameMessage;
import com.vingame.bot.domain.bot.message.GameMessageTypes;
import com.vingame.bot.domain.bot.message.StartGameMd5Message;
import com.vingame.bot.domain.bot.message.StartGameMessage;
import com.vingame.bot.domain.bot.message.SubscribeMessage;
import com.vingame.bot.domain.bot.message.UpdateBetMessage;

/**
 * Message types provider for the B52 message shapes.
 * <p>
 * <b>Deliberately not registered.</b> It carries neither {@code @Component} nor
 * {@code @MessageTypesImpl}, so {@code MessageTypesRegistry} never sees it — which
 * is exactly what {@code GameMessageTypesResolver}'s switch did before
 * PLUGIN_HOT_RELOAD Phase 2c: product 098 (whose {@code ProductCode} name is
 * {@code P_098("098", "B52", …)}) resolved to {@link
 * com.vingame.bot.domain.bot.message.g2.bom.BomGameMessageTypes}. A refactor must
 * not change which classes a product's frames parse with, so 2c preserved that
 * exactly, and {@code MessageTypesRegistryTest.b52IsNotRegistered} pins it in both
 * directions. Annotating this class for 098 would be a behaviour change, not a
 * completion.
 * <p>
 * <b>Open question — this is not vestigial code, and 098 may have been misparsing
 * in production</b> (review-2c F5). An earlier version of this javadoc said the
 * class "was reachable only from tests", which reads as leftovers. The evidence
 * points the other way:
 * <ul>
 *   <li>{@code ProductCode.P_098("098", "B52", "bc114098", …)} — 098 <em>is</em> the
 *       B52 brand, with its own appId. It is not an alias of 097/BOM.</li>
 *   <li>{@code AuthStrategyFactory:54-60} gives P_098 its own
 *       {@code B52LoginRequest}, deliberately distinct from P_097's
 *       {@code BomLoginRequest} at {@code :47-53}. The <b>auth layer already treats
 *       098 as its own brand</b> while the message layer sends it to BOM's
 *       classes.</li>
 *   <li>A complete five-class parallel family exists and is actively tested:
 *       {@link B52SubscribeMessage}, {@link B52StartGameMessage},
 *       {@link B52StartGameMd5Message}, {@link B52UpdateBetMessage},
 *       {@link B52EndGameMessage}, exercised by {@code HasCrowdBetsTest},
 *       {@code HasJackpotPoolTest} and {@code EndGameMessageSessionIdTest}.
 *       Somebody captured 098's frames and wrote five classes against them.</li>
 * </ul>
 * That leaves exactly two possibilities and no third:
 * <ol>
 *   <li>B52's wire shapes are identical to BOM's, in which case this whole
 *       {@code g2/b52} family is duplicate dead code carrying its own test suite;
 *       or</li>
 *   <li>098's bots have been parsing B52 frames with BOM classes for as long as 098
 *       has been wired — the same class of silent-misparse defect already on record
 *       for BOM winnings, where {@code BomEndGameMessage} implements
 *       {@code HasJackpot} but not {@code HasBotWinnings}, so BOM payout/RTP always
 *       read 0.</li>
 * </ol>
 * Resolving it needs captured 098 frames diffed against both families, which is
 * evidence nobody has gathered. Until then <b>do not "complete" the wiring by
 * annotating this class</b>: if (2) is true, annotating it is the fix, but shipping
 * it blind swaps one unverified parse for another on a live brand.
 */
public class B52GameMessageTypes implements GameMessageTypes {

    @Override
    public Class<? extends SubscribeMessage> subscribeType() {
        return B52SubscribeMessage.class;
    }

    @Override
    public Class<? extends StartGameMessage> startGameType() {
        return B52StartGameMessage.class;
    }

    @Override
    public Class<? extends StartGameMd5Message> startGameMd5Type() {
        return B52StartGameMd5Message.class;
    }

    @Override
    public Class<? extends UpdateBetMessage> updateBetType() {
        return B52UpdateBetMessage.class;
    }

    @Override
    public Class<? extends EndGameMessage> endGameType() {
        return B52EndGameMessage.class;
    }
}
