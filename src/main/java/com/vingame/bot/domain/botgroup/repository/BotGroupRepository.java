package com.vingame.bot.domain.botgroup.repository;

import com.vingame.bot.domain.botgroup.model.BotGroup;
import com.vingame.bot.domain.botgroup.model.BotGroupStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface BotGroupRepository extends MongoRepository<BotGroup, String> {

    List<BotGroup> findByEnvironmentId(String environmentId);

    List<BotGroup> findByGameId(String gameId);

    List<BotGroup> findByTargetStatus(BotGroupStatus targetStatus);
}
