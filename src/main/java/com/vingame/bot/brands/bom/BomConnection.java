package com.vingame.bot.brands.bom;

import com.vingame.bot.authentication.Connector;
import com.vingame.bot.authentication.store.ConnectionData;
import com.vingame.bot.authentication.store.ConnectionDataStore;
import com.vingame.webocketparser.VingameWebsocketClient;
import com.vingame.webocketparser.auth.AuthenticationHttpAccessTokenSupplier;

public class BomConnection {

    public static VingameWebsocketClient createConnection(String username, String password) {
        ConnectionData data = ConnectionDataStore.get("097");

        Connector connector = new Connector();
        connector.setUri(data.getUri());
        connector.setHeaders(data.getHeaders());
        connector.setZoneName("MiniGame3");
        connector.setEncryption(true);
        connector.setAccessTokenSupplier(new AuthenticationHttpAccessTokenSupplier(
                "https://apigw-bomwin.sgame.us",
                username,
                password,
                "bc114097"));

        return connector.newConnection();
    }
}
