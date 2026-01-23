package com.vingame.bot.domain.environment.repository;

import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.model.ProductCode;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface EnvironmentRepository extends MongoRepository<Environment, String> {

    List<Environment> findByType(EnvironmentType type);

    List<Environment> findByBrandCode(BrandCode brandCode);

    List<Environment> findByProductCode(ProductCode productCode);
}
