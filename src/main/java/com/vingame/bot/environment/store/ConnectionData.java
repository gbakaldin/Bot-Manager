package com.vingame.bot.environment.store;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.net.URI;
import java.util.Map;

@Getter
@Setter
@RequiredArgsConstructor
public class ConnectionData {

    private final URI uri;
    private final Map<String, String> headers;

    private final String apiGateway;
    private final String appId;

    private final String zoneName;
    private final boolean encryption;

}
