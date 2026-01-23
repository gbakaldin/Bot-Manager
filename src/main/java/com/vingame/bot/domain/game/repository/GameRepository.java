package com.vingame.bot.domain.game.repository;

import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.ProductCode;
import com.vingame.bot.domain.game.model.Game;
import com.vingame.bot.domain.game.model.GameType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface GameRepository extends MongoRepository<Game, String> {

    List<Game> findByBrandCodeAndProductCode(BrandCode brandCode, ProductCode productCode);

    List<Game> findByGameType(GameType gameType);
}
