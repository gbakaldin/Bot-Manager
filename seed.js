// Seed script for Bot Manager MongoDB
// Run with: mongosh mongodb://localhost:27017/botmanager seed.js
// Idempotent - safe to re-run (uses replaceOne with upsert)

db = db.getSiblingDB('botmanager');

// --- Environments ---

db.environments.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207768" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207768",
        name: "097 Staging",
        type: "STAGING",
        brandCode: "G2",
        productCode: "P_097",
        webSocketMiniUrl: "wss://bomwsk-gpg.sgame.club/websocket_mini",
        webSocketCardUrl: "EMPTY",
        hostUrl: "https://bomwsk-gpg.sgame.club",
        apiGatewayUrl: "https://apigw-bomwin.sgame.us",
        appId: "bc114097",
        headers: {
            "Host": "bomwsk-gpg.sgame.club",
            "Connection": "Upgrade",
            "Pragma": "no-cache",
            "Cache-Control": "no-cache",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            "Upgrade": "websocket",
            "Origin": "https://097.stgame.win",
            "Sec-WebSocket-Version": "13",
            "Accept-Encoding": "gzip, deflate, br, zstd",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-WebSocket-Extensions": "permessage-deflate; client_max_window_bits"
        },
        customZone: true,
        binaryFrame: true,
        miniZoneName: "MiniGame3",
        cardZoneName: "Simms1",
        encryptionKey: "o7b4AcvxnV2Ozsg39D3J9A==",
        encryptionIv: "Me0piCDMRLWnZ581Qc338g==",
        alertOnLowBalance: true,
        periodicLogoutEnabled: null,
        periodicLogoutIntervalMinutes: null,
        useJwtAuth: false
    },
    { upsert: true }
);

db.environments.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207769" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207769",
        name: "118 Staging",
        type: "STAGING",
        brandCode: "G4",
        productCode: "P_118",
        webSocketMiniUrl: "wss://nohusk.sgame.club/websocket_mini",
        webSocketCardUrl: "EMPTY",
        hostUrl: "https://nohusk.sgame.club",
        apiGatewayUrl: "https://apigw-nohu.sgame.us",
        appId: "bc112118",
        headers: {
            "Host": "nohusk.sgame.club",
            "Connection": "Upgrade",
            "Pragma": "no-cache",
            "Cache-Control": "no-cache",
            "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36",
            "Upgrade": "websocket",
            "Origin": "https://118.stgame.win",
            "Sec-WebSocket-Version": "13",
            "Accept-Encoding": "gzip, deflate, br, zstd",
            "Accept-Language": "en-US,en;q=0.9",
            "Sec-WebSocket-Extensions": "permessage-deflate; client_max_window_bits"
        },
        customZone: false,
        binaryFrame: false,
        miniZoneName: "MiniGame",
        cardZoneName: null,
        encryptionKey: null,
        encryptionIv: null,
        alertOnLowBalance: true,
        periodicLogoutEnabled: null,
        periodicLogoutIntervalMinutes: null
    },
    { upsert: true }
);

db.environments.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207770" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207770",
        name: "098 Staging",
        type: "STAGING",
        brandCode: "G2",
        productCode: "P_098",
        webSocketMiniUrl: "wss://api-b52-staging.stgame.win/websocket_mini",
        webSocketCardUrl: "EMPTY",
        hostUrl: "https://api-b52-staging.stgame.win",
        apiGatewayUrl: "https://api-gw52.sgame.us",
        appId: "b52.de",
        headers: {
            "Accept-encoding": "gzip, deflate, br, zstd",
            "Accept-language": "en-US,en;q=0.9",
            "Cache-control": "no-cache",
            "Connection": "Upgrade",
            "Host": "api-b52-staging.stgame.win",
            "Origin": "https://b52.stgame.win",
            "Pragma": "no-cache",
            "Sec-websocket-extensions": "permessage-deflate; client_max_window_bits",
            "Sec-websocket-version": "13",
            "Upgrade": "websocket",
            "User-agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
        },
        customZone: false,
        binaryFrame: false,
        miniZoneName: "MiniGame",
        cardZoneName: "Simms",
        encryptionKey: null,
        encryptionIv: null,
        alertOnLowBalance: true,
        periodicLogoutEnabled: null,
        periodicLogoutIntervalMinutes: null
    },
    { upsert: true }
);

// --- Games ---

db.games.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207761" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207761",
        brandCode: "G2",
        productCode: "P_097",
        name: "TaiXiu Seven",
        description: "",
        gameType: "BETTING_MINI",
        pluginName: "taiXiuSevenPlugin",
        gameId: null,
        numberOfOptions: 10,
        offset: 8000,
        md5: true
    },
    { upsert: true }
);

db.games.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207762" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207762",
        brandCode: "G4",
        productCode: "P_118",
        name: "BauCua",
        description: "",
        gameType: "BETTING_MINI",
        pluginName: "gourdCrabPlugin",
        gameId: null,
        numberOfOptions: 6,
        offset: 2000,
        md5: false
    },
    { upsert: true }
);

db.games.replaceOne(
    { _id: "3cda38f9-2c3d-465f-a52a-18ce83207763" },
    {
        _id: "3cda38f9-2c3d-465f-a52a-18ce83207763",
        brandCode: "G2",
        productCode: "P_098",
        name: "BauCua",
        description: "",
        gameType: "BETTING_MINI",
        pluginName: "gourdCrabMiniPlugin",
        gameId: null,
        numberOfOptions: 6,
        offset: 6000,
        md5: false
    },
    { upsert: true }
);

// --- Bot Groups ---

db.botGroups.replaceOne(
    { _id: "111" },
    {
        _id: "111",
        name: "Test group 097",
        environmentId: "3cda38f9-2c3d-465f-a52a-18ce83207768",
        namePrefix: "bcB0Ttest",
        password: "123bcB0Ttest123",
        gameId: "3cda38f9-2c3d-465f-a52a-18ce83207761",
        botCount: 1,
        minBet: 5000,
        maxBet: 100000,
        betIncrement: 5000,
        maxTotalBetPerRound: 2000000,
        minBetsPerRound: 0,
        maxBetsPerRound: 25,
        timeBased: false,
        timeFrom: null,
        timeUntil: null,
        chatEnabled: false,
        autoDepositEnabled: true,
        targetStatus: null,
        scheduledRestartTime: null,
        lastStartedAt: null,
        lastStoppedAt: null,
        lastFailureReason: null
    },
    { upsert: true }
);

db.botGroups.replaceOne(
    { _id: "222" },
    {
        _id: "222",
        name: "Test group 118",
        environmentId: "3cda38f9-2c3d-465f-a52a-18ce83207769",
        namePrefix: "gleb00",
        password: "123123",
        gameId: "3cda38f9-2c3d-465f-a52a-18ce83207762",
        botCount: 1,
        minBet: 5000,
        maxBet: 100000,
        betIncrement: 5000,
        maxTotalBetPerRound: 2000000,
        minBetsPerRound: 0,
        maxBetsPerRound: 25,
        timeBased: false,
        timeFrom: null,
        timeUntil: null,
        chatEnabled: false,
        autoDepositEnabled: true,
        targetStatus: null,
        scheduledRestartTime: null,
        lastStartedAt: null,
        lastStoppedAt: null,
        lastFailureReason: null
    },
    { upsert: true }
);

/*USER_PREFIX: 'test_bcmini_00',
    PASSWORD: "ZVeB7rH55h#",*/

db.botGroups.replaceOne(
    { _id: "9b54e101-2640-40db-a367-36e088d23cd8" },
    {
        _id: "9b54e101-2640-40db-a367-36e088d23cd8",
        name: "BC Mini bot group",
        environmentId: "3cda38f9-2c3d-465f-a52a-18ce83207770",
        namePrefix: "test_bcmini_00",
        password: "ZVeB7rH55h#",
        gameId: "3cda38f9-2c3d-465f-a52a-18ce83207763",
        botCount: 10,
        minBet: 5000,
        maxBet: 300000,
        betIncrement: 5000,
        maxTotalBetPerRound: 3000000,
        minBetsPerRound: 0,
        maxBetsPerRound: 25,
        timeBased: false,
        timeFrom: null,
        timeUntil: null,
        chatEnabled: false,
        autoDepositEnabled: true,
        targetStatus: null,
        scheduledRestartTime: null,
        lastStartedAt: null,
        lastStoppedAt: null,
        lastFailureReason: null
    },
    { upsert: true }
);

db.botGroups.replaceOne(
    { _id: "f20a0fcc-0857-4748-aaa8-8edf3fdb115e" },
    {
        id: "f20a0fcc-0857-4748-aaa8-8edf3fdb115e",
        name: "Auth test with new auth path",
        environmentId: "3cda38f9-2c3d-465f-a52a-18ce83207768",
        namePrefix: "testauthbot",
        password: "Aa123123aA",
        gameId: "3cda38f9-2c3d-465f-a52a-18ce83207761",
        botCount: 10,
        minBet: 5000,
        maxBet: 300000,
        betIncrement: 5000,
        maxTotalBetPerRound: 5000000,
        minBetsPerRound: 0,
        maxBetsPerRound: 30,
        timeBased: false,
        chatEnabled: false,
        autoDepositEnabled: true,
        targetStatus: null,
        scheduledRestartTime: null,
        lastStartedAt: null,
        lastStoppedAt: null,
        lastFailureReason: null
    },
    { upsert: true }
)

print("Seed data inserted successfully:");
print("  - " + db.environments.countDocuments() + " environments");
print("  - " + db.games.countDocuments() + " games");
print("  - " + db.botGroups.countDocuments() + " bot groups");


/*
{
    "name": "Auth test with socket",
    "environmentId": "3cda38f9-2c3d-465f-a52a-18ce83207768",
    "namePrefix": "authtestws97",
    "password": "Aa123AA123aA",
    "gameId": "3cda38f9-2c3d-465f-a52a-18ce83207761",
    "botCount": 15,
    "minBet": 5000,
    "maxBet": 300000,
    "betIncrement": 5000,
    "maxTotalBetPerRound": 5000000,
    "minBetsPerRound": 0,
    "maxBetsPerRound": 30,
    "timeBased": false,
    "chatEnabled": false,
    "autoDepositEnabled": true
}

Cannot find user name for user 29_2768
Cannot find user name for user 29_2768
Cannot find user name for user 29_2768
Cannot find user name for user 29_2768
Cannot find user name for user 29_2768

Cannot find user name for user 29_2526
Cannot find user name for user 29_2526
Cannot find user name for user 29_2526
Cannot find user name for user 29_2526
Cannot find user name for user 29_2526

Cannot find user name for user 29_1165
Cannot find user name for user 29_1165
Cannot find user name for user 29_1165
Cannot find user name for user 29_1165
Cannot find user name for user 29_1165



[Register] POST https://apigw-bomwin.sgame.us/gwms/v1/bot/register.aspx | X-TOKEN: 58bc2820612d23c34fe43d0b2c6f7223 | body: {"register_ip":"16.162.36.69","app_id":"bc114097","username":"authtestws9711","password":"Aa123AA123aA","ip":"16.162.36.69","os":"OS X","device":"Computer","browser":"chrome","source":"bc114097"}
    bot-manager-1  | 13:11:48.972 [user-registration-4] ERROR ApiGatewayClient [//] - Failed to register authtestws975: Registration failed: IP denied, you cant used this function (status: UNAUTHORIZED, code: 401)
bot-manager-1  | 13:11:48.973 [user-registration-12] INFO  ApiGatewayClient [//] - [Register] POST https://apigw-bomwin.sgame.us/gwms/v1/bot/register.aspx | X-TOKEN: 58bc2820612d23c34fe43d0b2c6f7223 | body: {"register_ip":"16.162.36.69","app_id":"bc114097","username":"authtestws9713","password":"Aa123AA123aA","ip":"16.162.36.69","os":"OS X","device":"Computer","browser":"chrome","source":"bc114097"}
*/
