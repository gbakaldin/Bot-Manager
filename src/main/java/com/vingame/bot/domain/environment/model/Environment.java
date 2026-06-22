package com.vingame.bot.domain.environment.model;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "environments")
public class Environment {

    /**
     * Product-default WebSocket zone name for mini-games. Used when
     * {@link #customZone} is {@code false} and the game type maps to mini.
     * RESTART_LIFECYCLE_FIX Architecture Decision 2.
     */
    public static final String DEFAULT_MINI_ZONE_NAME = "MiniGame";

    /**
     * Product-default WebSocket zone name for card/other games. Used when
     * {@link #customZone} is {@code false} and the game type is not mini.
     * RESTART_LIFECYCLE_FIX Architecture Decision 2.
     */
    public static final String DEFAULT_CARD_ZONE_NAME = "Simms";

    @Id
    private String id;

    private String name;
    private EnvironmentType type;
    private BrandCode brandCode;
    private ProductCode productCode;

    private String webSocketMiniUrl;
    private String webSocketCardUrl;

    private String hostUrl;
    private String apiGatewayUrl;

    private String appId;

    private Map<String, String> headers;

    private boolean customZone;
    private boolean binaryFrame;

    private String miniZoneName;
    private String cardZoneName;

    private String encryptionKey;
    private String encryptionIv;

    private boolean alertOnLowBalance;

    // When true, jwtToken (token2) is required and passed as WebSocket subprotocol during handshake
    private boolean useJwtAuth;

    // Periodic logout configuration (null = use global defaults from application.properties)
    private Boolean periodicLogoutEnabled;
    private Integer periodicLogoutIntervalMinutes;

    /**
     * Resolve the effective WebSocket zone name for this environment and the given game.
     * <p>
     * When {@link #customZone} is {@code true}, returns the per-environment custom field
     * ({@link #miniZoneName} for mini games, {@link #cardZoneName} for everything else).
     * When {@code false}, returns the product-default constants
     * ({@link #DEFAULT_MINI_ZONE_NAME} / {@link #DEFAULT_CARD_ZONE_NAME}).
     * <p>
     * A game is considered mini iff its game type is {@link GameType#BETTING_MINI} or
     * {@link GameType#SLOT} (slot games share the mini zone).
     * If {@code game} or its game type is {@code null}, the card/default branch is taken —
     * the resolver never throws on null and never returns {@code null} on the default path.
     * <p>
     * See RESTART_LIFECYCLE_FIX Architecture Decision 1.
     *
     * @param game the game whose zone is being resolved
     * @return the resolved zone name; may be {@code null} only when {@code customZone=true}
     *         and the corresponding custom field is itself {@code null} (a real misconfig
     *         that callers should surface)
     */
    public String resolveZoneName(Game game) {
        boolean mini = game != null
                && (game.getGameType() == GameType.BETTING_MINI || game.getGameType() == GameType.SLOT);
        if (customZone) {
            return mini ? miniZoneName : cardZoneName;
        }
        return mini ? DEFAULT_MINI_ZONE_NAME : DEFAULT_CARD_ZONE_NAME;
    }
}
