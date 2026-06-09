# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bot Manager is a Spring Boot application for orchestrating game bots that interact with mini-game servers via WebSocket connections. Built on top of the [WebSocket Parser](https://github.com/vingame/websocket-parser) library.

## Build & Run Commands

```bash
mvn clean install      # Build the project
mvn spring-boot:run    # Run the application
mvn package            # Package the application
```

The main entry point is `com.vingame.bot.Starter` (Spring Boot application).

## Technology Stack

- **Java 21** with Virtual Threads (Project Loom)
- **Spring Boot 4.0.0** with Spring Web
- **Maven** for dependency management
- **MongoDB** via Spring Data MongoDB
- **Lombok** for boilerplate reduction
- **MapStruct 1.6.3** for DTO/Entity mapping
- **Jackson** for JSON serialization/deserialization with polymorphism support
- **Log4j2** (via slf4j2) for logging
- **OpenAPI/Swagger** (SpringDoc) at `/swagger-ui.html`
- **WebSocket Parser** (custom library: `vingame:websocket-parser:1.0-SNAPSHOT`)

## Package Structure

```
com.vingame.bot/
├── config/                        # Spring configuration
│   ├── bot/                       # BotConfiguration, BotBehaviorConfig, BotCredentials
│   └── client/                    # EnvironmentClientRegistry, EnvironmentClients
├── common/                        # Shared exceptions, utilities
├── domain/
│   ├── bot/
│   │   ├── core/                  # Bot, BettingMiniGameBot
│   │   ├── implementation/        # BauCuaBot, BauCuaMiniBot, TaiXiuSevenBot
│   │   ├── message/               # Message interfaces
│   │   │   ├── request/           # Request messages (Bet, Chat, Subscribe, etc.)
│   │   │   ├── g2/bom/            # BOM product message implementations
│   │   │   └── g4/nohu/           # Nohu product message implementations
│   │   ├── service/               # BotFactory
│   │   └── util/                  # SessionIdStore, GameState, OutputPrinter
│   ├── botgroup/                  # BotGroup domain (controller, service, model, dto)
│   ├── environment/               # Environment domain
│   └── game/                      # Game domain (offset, pluginName, numberOfOptions)
└── infrastructure/
    ├── client/                    # ApiGatewayClient, GameMsClient, ClientFactory
    └── runtime/                   # BotGroupRuntime
```

## Architecture

### Bot Lifecycle Flow

```
BotFactory.createBot() → build with credentials → authenticate()
→ get [authToken, agencyToken] → build client with tokens → start()
```

### Key Architecture Decisions

- **Bots are POJOs**: Created via builders and factories, no custom Spring scope
- **Stateless clients**: One `ApiGatewayClient`/`GameMsClient` per Environment (shared by all bots)
- **Two-token system**: Auth token (WebSocket) + Agency token (monetary ops) - non-interchangeable
- **Configuration-driven bots**: `BettingMiniGameBot` is concrete; game types handled via `Game` entity and `GameMessageTypes`
- **Virtual threads everywhere**: Schedulers, bot creation, health monitoring all use virtual threads for lightweight concurrency

### Token Naming Reference

The same token is called different things in different contexts — this is a known mess:

| Internal name | Register response field | `verifytoken` `?token=` param | Role |
|---|---|---|---|
| `authToken` | `session_id` | `token` | WebSocket authentication, `X-TOKEN` for user update |
| `agencyToken` | `token` | — | Monetary ops |
| `jwtToken` (token2) | _(not yet in register response)_ | — | Merging JWT; will eventually replace both above |

`token2` / `jwtToken` is in active development and currently works alongside the other two. Once migration is complete it will replace both `authToken` and `agencyToken`.

### Parallel Execution

Bot creation and user registration use parallel execution with Semaphore-based rate limiting:

```
┌─────────────────────────────────────────────────┐
│  Semaphore (N permits)                          │
│  Controls max concurrent requests to server     │
│         ↓ acquire() / release() ↑              │
│  ┌─────────────────────────────────────────┐   │
│  │   Virtual Thread Pool (unbounded)       │   │
│  │   100 tasks submitted, N run at a time  │   │
│  └─────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

**Configuration** (`application.properties`):
```properties
bot.creation.parallelism=10       # Max concurrent bot authentications during group start
user.registration.parallelism=10  # Max concurrent user registrations during group creation
```

This pattern provides explicit rate limiting to avoid overwhelming the game server's auth endpoint, while virtual threads handle the I/O-bound waiting efficiently.

### Message System

Messages use Jackson JSON polymorphism with dynamic type registration:

- **CODE** = Message type identifier (3000=subscribe, 3002=updateBet, 3005=startGame, 3006=endGame)
- **OFFSET** = Game identifier (2000=BauCua, 8000=TaiXiuSeven)
- **CMD** = CODE + OFFSET (actual JSON value)

```java
mapper.registerSubtypes(messageTypes.getTypeRegistrations(offset, game.isMd5()));
```

## REST API

### BotGroupController - `/api/v1/bot-group`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{id}` | Find bot group by ID |
| GET | `/` | List all bot groups |
| POST | `/filter/` | Filter bot groups |
| POST | `/` | Create new bot group |
| PATCH | `/` | Update bot group |
| DELETE | `/{id}` | Delete bot group |
| POST | `/{id}/start` | Start all bots in group |
| POST | `/{id}/stop` | Stop all bots in group |
| POST | `/{id}/restart` | Restart all bots in group |

### EnvironmentController - `/api/v1/environment`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{id}` | Find environment by ID |
| GET | `/` | List all environments |
| POST | `/filter/` | Filter environments |
| POST | `/` | Create new environment |
| PATCH | `/` | Update environment |
| DELETE | `/{id}` | Delete environment |

### GameController - `/api/v1/game`

CRUD operations for game configurations.

## Core Classes

### Bot (`domain/bot/core/Bot.java`)

Abstract base class for all bots:
- Authentication via `ApiGatewayClient`
- WebSocket lifecycle (connect, restart, stop)
- Balance management with auto-deposit
- Abstract methods: `shouldBet()`, `resolveBetAmount()`, `resolveBetCondition()`, `botBehaviorScenario()`, `onStart()`

### BettingMiniGameBot (`domain/bot/core/BettingMiniGameBot.java`)

Concrete bot for all BettingMini game types. Configured via `Game` entity and `BotBehaviorConfig`:
- Game state management (BET vs PAYOUT phases)
- Session tracking via `SessionIdStore`
- Countdown timer prevents late bets
- Scenario-based message flow (subscribe → start → bet → end)

### Key Patterns

**Betting Logic Flow:**
1. `canBet()`: session exists, BET phase, time remaining
2. `shouldBet()`: bot-specific decision logic
3. `resolveBetAmount()`: calculate bet size
4. `resolveNextEntryToBet()`: select position
5. Send bet via `Request.bet()`
6. `creditBalance(amount)`: decrement local balance

**Dual Status Tracking:**
- `targetStatus` in entity (DB) - what admin wants
- `actualStatus` in `BotGroupRuntime` (memory) - current state

## OpenAPI Documentation

- Swagger UI: http://localhost:8080/swagger-ui.html
- API Docs: http://localhost:8080/v3/api-docs

---

## Known Bugs

### WebSocket AUTH Race Condition (VingameWebSocketClient)

**Symptom:** Some bots silently fail to authenticate with the WebSocket server. They show `WARN "Cannot send message, not connected"` immediately after logging the AUTH message during startup, then get dropped by the server ~30 seconds later. Their scheduler threads (`pool-X-thread-1`) then spin indefinitely with the same WARN until the health monitor marks the group DEAD.

**Root cause:** The `connected` flag is set by the bot-creation thread, but the AUTH message is sent from the Netty IO thread (`multiThreadIoEventLoopGroup`) inside the `userEventTriggered(WebSocketHandshakeCompletionEvent)` handler. If the IO thread fires the handshake event before the bot-creation thread sets `connected = true`, the AUTH send is blocked. The flag check silently drops the message with only a WARN — no retry, no reconnect, no exception.

**How to confirm in logs:** Look for this pattern on the same IO thread in rapid succession:
```
[multiThreadIoEventLoopGroup-2-N] INFO  VingameWebSocketClient - Client ws-client-XXXX: WebSocket handshake completed
[multiThreadIoEventLoopGroup-2-N] INFO  VingameWebSocketClient - AUTH [1,"MiniGame3","","",{"accessToken":"..."}]
[multiThreadIoEventLoopGroup-2-N] WARN  VingameWebSocketClient - Client ws-client-XXXX: Cannot send message, not connected
```
Every client that shows this 3-line pattern will be dropped by the server. Clients where AUTH succeeds show no WARN and their bot threads log "Connected to server" before the IO thread fires the handshake event.

**Fix:** Set `connected = true` in the handshake completion handler on the IO thread itself, before sending AUTH. Do not rely on the bot-creation thread to set the flag — by the time it runs, the IO thread may have already attempted (and dropped) the AUTH message.

**Secondary issue:** No reconnection logic exists for any bot. When `channelInactive` fires (server closes connection), nothing triggers a reconnect. The bot's scheduler threads keep trying to send messages in a tight loop until the health monitor intervenes. This affects both the 10 AUTH-failed bots and any bots that disconnect mid-session.

### Server-Side Subscriber Pruning (Silent Zombie Bots)

**Symptom:** A subset of bots stops receiving game messages mid-session while their WebSocket connection remains alive. No disconnect event fires, no error is logged, the bot's status stays `CONNECTION_AUTHENTICATED`. From the frontend, user count appears to drop gradually over the first few rounds.

**Observed in logs:** With 15 bots, all 15 received StartGame/EndGame for rounds sid:422069 and sid:422070. After the EndGame for sid:422070 (10:24:33), the game server sent StartGame for sid:422071 to only 10 bots — the other 5 received nothing further for the rest of the session (10+ minutes). The 5 silent bots had intact WS connections (`onDisconnect` never fired) and were otherwise healthy.

**Root cause (suspected):** Game server enforces a subscriber limit per game channel (~10 based on observed behavior). When the limit is exceeded, the server silently evicts excess subscribers without closing their WebSocket connection or sending any error frame. The bots have no way to detect this — they are connected but invisible to the game.

**How to confirm:** Run with N > suspected limit. After 1–2 rounds, count unique usernames in StartGame log entries. If fewer than N bots appear, the others have been pruned. Verify with `grep "cmd.*11005" logs | grep -o "authtestws[0-9]*" | sort -u | wc -l`.

**Fix (bot side — pending server investigation):** Add a watchdog timer in `BettingMiniGameBot`. After `onSubscribe` fires, start a timer that resets on every received game message (`onStartGame`, `onUpdate`, `onEndGame`). If the timer expires without a message (e.g., 2× expected round duration with no activity), the bot should re-subscribe or reconnect. This turns a silent zombie into a recoverable state.

**Note:** Investigate server behavior first — the limit may be configurable or may be a bug on the server side. If the server limit is intentional, the bot-side watchdog is still needed to detect and recover from eviction.

---

## Current Status (Jan 2026)

### Completed Features

- ✅ Spring Boot REST API with OpenAPI documentation
- ✅ Environment/BotGroup/Game CRUD via REST API
- ✅ Bot group start/stop/restart/status
- ✅ `BotFactory` for dynamic bot creation
- ✅ Dual-status tracking (target vs actual)
- ✅ Health monitoring (30s interval)
- ✅ Concrete `BettingMiniGameBot` with configuration-driven game types
- ✅ `GameMessageTypes` for dynamic JSON polymorphism
- ✅ Stateless clients per Environment
- ✅ Random username generation from 5k name file
- ✅ UI for environment and bot group management
- ✅ Virtual threads throughout the application (schedulers, bot creation, health monitoring)
- ✅ Parallel user registration and bot authentication with configurable concurrency

### Backlog

**Priority (Internal Testing Feedback):**
- [x] Direct migration of existing bots - when `existingGroup = true` flag is set in POST `/api/v1/bot-group`, skip user registration and add bots directly to database
- [x] Configurable betting options per game - replace linear `0..maxOptions` with predefined lists (e.g., `[1, 10, 100]`) stored in `Game` entity
- [ ] Configurable betting values per game - define allowed bet amounts (e.g., `[100, 500, 1000]`) instead of arbitrary values to pass server validation

**Infrastructure:**
- [x] MongoDB integration (replace in-memory storage)
- [ ] CI/CD pipeline setup
- [x] Review and improve Docker configuration
- [ ] Add Grafana for observability, connect Loki for log aggregation

**Architecture:**
- [ ] Spring Plugin Support Framework - move bot scripts and messages to separate plugin module/repository for hot-reload without full restart
- [ ] Time-based activation (`timeFrom`/`timeUntil` in BotGroup)
- [x] Periodic logout logic - one bot per group logs out per hour (round-robin), configurable via `application.properties` (environment-dependent)

**Code Quality:**
- [ ] Add more unit and component tests
- [ ] Add clearer Exception system with proper hierarchy
- [ ] Improve logging - clearer separation between INFO and DEBUG levels
- [ ] Replace all deprecated API usage, remove deprecated classes and methods
- [ ] Review `Bot.java` methods (`connectToSocket`, `restart`, etc.) - determine if still needed or can be simplified
- [ ] Pre-flight username length validation in `BotGroupService.save` — auth gateway caps usernames per product (Tip/P_116 = 12 chars). Reject `namePrefix.length() + String.valueOf(botCount).length() > cap` before fan-out to save N wasted auth calls and surface a clean 400 instead of forwarding all N upstream errors.

**Monitoring:**
- [ ] Add API endpoints for monitoring
- [x] Actuator endpoints (health, info, metrics, loggers) on main port
- [ ] Advanced health monitoring with diagnostics (see Health Diagnostics below)

### Health Diagnostics (Planned)

Diagnostic checks to surface why bots can't start or aren't performing correctly. Some overlap with back office, but not everyone who needs these metrics has back office access.

| Check | Condition | Severity |
|---|---|---|
| **Game server down** | Bots cannot connect to WebSocket | Critical |
| **Auth down** | Bots cannot authenticate or register | Critical |
| **Game down** | WS connected but no messages for >1 min, or cannot subscribe to game subchannel | Critical |
| **Bot down** | Thread dead or unresponsive | Error |
| **Balance low** | Bot out of balance or short on balance | Warning |
| **RTP anomaly** | Bots making net gains over prolonged period (RTP >100%) | Critical — indicates game logic issue |
