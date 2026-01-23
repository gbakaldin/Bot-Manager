package com.vingame.bot.domain.environment.service;

import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.repository.EnvironmentRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class EnvironmentService {

    private final EnvironmentRepository repository;
    private final EnvironmentMapper mapper;
    private final MongoTemplate mongoTemplate;

    public EnvironmentService(EnvironmentRepository repository, EnvironmentMapper mapper, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
    }

    public Environment findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found"));
    }

    public List<Environment> findAll() {
        return repository.findAll();
    }

    public List<Environment> filter(EnvironmentFilter filter) {
        Query query = new Query();
        if (filter.getType() != null) {
            query.addCriteria(Criteria.where("type").is(filter.getType()));
        }
        if (filter.getName() != null) {
            query.addCriteria(Criteria.where("name").regex("^" + Pattern.quote(filter.getName()) + "$", "i"));
        }
        if (filter.getBrandCode() != null) {
            query.addCriteria(Criteria.where("brandCode").is(filter.getBrandCode()));
        }
        if (filter.getProductCode() != null) {
            query.addCriteria(Criteria.where("productCode").is(filter.getProductCode()));
        }
        return mongoTemplate.find(query, Environment.class);
    }

    public Environment save(Environment environment) {
        if (environment.getId() == null || environment.getId().isEmpty()) {
            environment.setId(UUID.randomUUID().toString());
        }
        return repository.save(environment);
    }

    public Environment update(String id, EnvironmentDTO updateDTO) {
        Environment existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return repository.save(existing);
    }

    public void delete(String id) {
        repository.deleteById(id);
    }
}
