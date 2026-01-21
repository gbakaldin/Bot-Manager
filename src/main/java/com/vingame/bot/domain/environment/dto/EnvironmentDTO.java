package com.vingame.bot.domain.environment.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vingame.bot.domain.environment.model.BrandCode;
import com.vingame.bot.domain.environment.model.EnvironmentType;
import com.vingame.bot.domain.environment.model.ProductCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvironmentDTO {

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

    private Boolean customZone;
    private Boolean binaryFrame;

    private String miniZoneName;
    private String cardZoneName;

    private String encryptionKey;
    private String encryptionIv;

    private Boolean alertOnLowBalance;
}
