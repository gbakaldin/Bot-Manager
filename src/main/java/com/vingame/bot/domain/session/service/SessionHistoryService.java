package com.vingame.bot.domain.session.service;

import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.session.dto.SessionHistoryDTO;
import com.vingame.bot.domain.session.mapper.SessionHistoryMapper;
import com.vingame.bot.domain.session.model.SessionHistory;
import com.vingame.bot.domain.session.repository.SessionHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class SessionHistoryService {

    private final SessionHistoryRepository repository;
    private final SessionHistoryMapper mapper;

    public SessionHistoryService(SessionHistoryRepository repository, SessionHistoryMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    public SessionHistory findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Session history not found with id: " + id));
    }

    public SessionHistory findBySessionId(String sessionId) {
        return repository.findBySessionId(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Session history not found for session: " + sessionId));
    }

    public List<SessionHistory> findAll() {
        return repository.findAll();
    }

    public List<SessionHistory> findByGameId(String gameId) {
        return repository.findByGameId(gameId);
    }

    public List<SessionHistory> findByEnvironmentId(String environmentId) {
        return repository.findByEnvironmentId(environmentId);
    }

    public List<SessionHistory> findByGameIdAndEnvironmentId(String gameId, String environmentId) {
        return repository.findByGameIdAndEnvironmentId(gameId, environmentId);
    }

    public List<SessionHistory> findJackpotSessions() {
        return repository.findByJackpotTrue();
    }

    public SessionHistory save(SessionHistory session) {
        return repository.save(session);
    }

    public SessionHistory update(String id, SessionHistoryDTO updateDTO) {
        SessionHistory existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return repository.save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
