package com.vingame.bot.domain.game.model;

import com.vingame.bot.domain.brand.model.BrandCode;
import com.vingame.bot.domain.brand.model.ProductCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.stream.IntStream;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "games")
public class Game {

    @Id
    private String id;
    private BrandCode brandCode;
    private ProductCode productCode;

    private String name;
    private String description;
    private GameType gameType;

    private String pluginName;
    private Integer gameId;
    private int numberOfOptions;
    private List<Integer> bettingOptions;
    private Integer offset;
    private boolean md5;

    /**
     * Returns the effective betting options list.
     * If bettingOptions is explicitly set, returns that list.
     * Otherwise, generates [0, 1, 2, ..., numberOfOptions-1].
     */
    public List<Integer> getEffectiveBettingOptions() {
        if (bettingOptions != null && !bettingOptions.isEmpty()) {
            return bettingOptions;
        }
        return IntStream.range(0, numberOfOptions).boxed().toList();
    }
}
