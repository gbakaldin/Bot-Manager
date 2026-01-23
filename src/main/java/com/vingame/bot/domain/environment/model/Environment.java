package com.vingame.bot.domain.environment.model;

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

    // Periodic logout configuration (null = use global defaults from application.properties)
    private Boolean periodicLogoutEnabled;
    private Integer periodicLogoutIntervalMinutes;
}
