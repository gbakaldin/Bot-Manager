package com.vingame.bot.environment.store;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class ConnectionDataStore {

    private static final Map<String, ConnectionData> infos = new HashMap<>();

    private ConnectionDataStore() {}

    static {
        infos.put("097", setup097());
        infos.put("111", setup111());
        infos.put("114", setup114());
    }

    private static ConnectionData setup097() {
        URI uri = URI.create("wss://bomwsk-gpg.sgame.club/websocket_mini");
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "bomwsk-gpg.sgame.club");
        headers.put("Connection", "Upgrade");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
        headers.put("Upgrade", "websocket");
        headers.put("Origin", "https://097.stgame.win");
        headers.put("Sec-WebSocket-Version", "13");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-WebSocket-Key", "7z1NZZ1NLSCOwL2heHS8pA==");
        headers.put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");

        return new ConnectionData(uri, headers, "https://apigw-bomwin.sgame.us", "bc114097", "MiniGame3", true);
    }

    private static ConnectionData setup111() {
        URI uri = URI.create("wss://ramagw-sock2.stgame.win/websocket_mini");
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "ramagw-sock2.stgame.win");
        headers.put("Connection", "Upgrade");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
        headers.put("Upgrade", "websocket");
        headers.put("Origin", "https://111.stgame.win");
        headers.put("Sec-WebSocket-Version", "13");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-WebSocket-Key", "8vnYvXp+JpKlqgQ/l6qRbw==");
        headers.put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");

        return new ConnectionData(uri, headers, "https://apigw-bomwin.sgame.us", "BC14097","MiniGame", false);
    }

    private static ConnectionData setup114() {
        URI uri = URI.create("wss://api-rikstaging.stgame.win/websocket_mini");
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "api-rikstaging.stgame.win");
        headers.put("Connection", "Upgrade");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
        headers.put("Upgrade", "websocket");
        headers.put("Origin", "https://rik.stgame.win");
        headers.put("Sec-WebSocket-Version", "13");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-WebSocket-Key", "PrmyxwizXVTKsGeHfixCRQ==");
        headers.put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");

        return new ConnectionData(uri, headers, "https://apigw-bomwin.sgame.us", "BC14097","MiniGame", false);
    }

    private static ConnectionData setup114Prod() {
        URI uri = URI.create("wss://myniskgw.ryksockesg.net/websocket");
        Map<String, String> headers = new HashMap<>();
        headers.put("Host", "myniskgw.ryksockesg.net");
        headers.put("Connection", "Upgrade");
        headers.put("Pragma", "no-cache");
        headers.put("Cache-Control", "no-cache");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36");
        headers.put("Upgrade", "websocket");
        headers.put("Origin", "https://i.rik.vip");
        headers.put("Sec-WebSocket-Version", "13");
        headers.put("Accept-Encoding", "gzip, deflate, br, zstd");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Sec-WebSocket-Key", "8vnYvXp+JpKlqgQ/l6qRbw==");
        headers.put("Sec-WebSocket-Extensions", "permessage-deflate; client_max_window_bits");

        return new ConnectionData(uri, headers, "https://apigw-bomwin.sgame.us", "BC14097","MiniGame", false);
    }

    public static ConnectionData get(String code) {
        return infos.get(code);
    }

}
