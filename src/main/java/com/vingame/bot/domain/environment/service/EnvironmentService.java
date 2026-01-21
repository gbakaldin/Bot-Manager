package com.vingame.bot.domain.environment.service;

import com.vingame.bot.domain.environment.dto.EnvironmentDTO;
import com.vingame.bot.common.exception.ResourceNotFoundException;
import com.vingame.bot.domain.environment.mapper.EnvironmentMapper;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.Environment;
import com.vingame.bot.domain.environment.model.EnvironmentFilter;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.model.ProductCode;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EnvironmentService {

    private final List<Environment> environments = new ArrayList<>();
    private final EnvironmentMapper mapper;

    public EnvironmentService(EnvironmentMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void prepopulateEnvironments() {
        Map<String, String> headers = new HashMap<>() {{
                put("Host", "bomwsk-gpg.sgame.club");
                put("Connection", "Upgrade");
                put("Pragma", "no-cache");
                put("Cache-Control", "no-cache");
                put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
                put("Upgrade", "websocket");
                put("Origin", "https://097.stgame.win");
                put("Sec-WebSocket-Version", "13");
                put("Accept-Encoding", "gzip, deflate, br, zstd");
                put("Accept-Language", "en-US,en;q=0.9");
                put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");
        }};

        environments.add(Environment.builder()
                .id("3cda38f9-2c3d-465f-a52a-18ce83207768")
                .name("097 Staging")
                .type(EnvironmentType.STAGING)
                .brandCode(BrandCode.G2)
                .productCode(ProductCode.P_097)
                .webSocketMiniUrl("wss://bomwsk-gpg.sgame.club/websocket_mini")
                .webSocketCardUrl("EMPTY")
                .hostUrl("https://bomwsk-gpg.sgame.club")
                .apiGatewayUrl("https://apigw-bomwin.sgame.us")
                .appId("bc114097")
                .headers(headers)
                .customZone(true)
                .binaryFrame(true)
                .miniZoneName("MiniGame3")
                .cardZoneName("Simms1")
                .encryptionKey("o7b4AcvxnV2Ozsg39D3J9A==")
                .encryptionIv("Me0piCDMRLWnZ581Qc338g==")
                .alertOnLowBalance(true)
                .build());

        Map<String, String> headers2 = new HashMap<>() {{
            put("Host", "nohusk.sgame.club");
            put("Connection", "Upgrade");
            put("Pragma", "no-cache");
            put("Cache-Control", "no-cache");
            put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
            put("Upgrade", "websocket");
            put("Origin", "https://118.stgame.win");
            put("Sec-WebSocket-Version", "13");
            put("Accept-Encoding", "gzip, deflate, br, zstd");
            put("Accept-Language", "en-US,en;q=0.9");
            put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");
        }};

        environments.add(Environment.builder()
                .id("3cda38f9-2c3d-465f-a52a-18ce83207769")
                .name("118 Staging")
                .type(EnvironmentType.STAGING)
                .brandCode(BrandCode.G4)
                .productCode(ProductCode.P_118)
                .webSocketMiniUrl("wss://nohusk.sgame.club/websocket_mini")
                .webSocketCardUrl("EMPTY")
                .miniZoneName("MiniGame")
                .hostUrl("https://nohusk.sgame.club")
                .apiGatewayUrl("https://apigw-nohu.sgame.us")
                .appId("bc112118")
                .headers(headers2)
                .alertOnLowBalance(true)
                .build());
    }

    public Environment findById(String id) {
        return environments.stream()
                .filter(e -> e.getId().equals(String.valueOf(id)))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found"));
    }

    public List<Environment> findAll() {
        return environments;
    }

    public List<Environment> filter(EnvironmentFilter filter) {
        return environments.stream()
                .filter(e -> filter.getType() == null || e.getType() == filter.getType())
                .filter(e -> filter.getName() == null || e.getName().equalsIgnoreCase(filter.getName()))
                .filter(e -> filter.getBrandCode() == null || e.getBrandCode() == filter.getBrandCode())
                .filter(e -> filter.getProductCode() == null || e.getProductCode() == filter.getProductCode())
                .toList();
    }

    public Environment save(Environment environment) {
        environment.setId(UUID.randomUUID().toString());
        environments.add(environment);
        return environment;
    }

    public Environment update(String id, EnvironmentDTO updateDTO) {
        Environment existing = findById(id);
        mapper.updateEntityFromDTO(updateDTO, existing);
        return existing;
    }

    public void delete(String id) {
        environments.removeIf(e -> e.getId().equals(id));
    }
}
