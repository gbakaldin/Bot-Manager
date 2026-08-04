package com.vingame.bot.domain.session.repository;

import com.vingame.bot.domain.session.model.SessionHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SessionHistoryRepository extends MongoRepository<SessionHistory, String> {

    Optional<SessionHistory> findBySessionId(String sessionId);

    List<SessionHistory> findByGameId(String gameId);

    List<SessionHistory> findByEnvironmentId(String environmentId);

    List<SessionHistory> findByGameIdAndEnvironmentId(String gameId, String environmentId);

    List<SessionHistory> findByJackpotTrue();
}
