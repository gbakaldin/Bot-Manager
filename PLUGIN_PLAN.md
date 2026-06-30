# Spring Plugin Framework Implementation Plan

## Goal
Decouple game-specific code (messages, bot scripts) into hot-reloadable plugins, enabling updates without stopping all bot groups - each affected group cycles (stop → restart) seamlessly before moving to the next.

---

## Architecture Overview

```
Bot Manager (Core Application)
│
├── bot-plugin-api/              # Interfaces only (GamePlugin, GameMessageTypes, base messages)
│
├── bot-plugin-core/             # Bot script implementations (BettingMiniGameBot, etc.)
│   └── depends on: bot-plugin-api
│
├── bot-core/                    # Main Spring Boot app + PluginManager
│   └── depends on: bot-plugin-api, bot-plugin-core
│
└── plugins/
    ├── bom-plugin/              # BOM (P_097) messages only
    │   └── depends on: bot-plugin-api
    └── nohu-plugin/             # NOHU (P_118) messages only
        └── depends on: bot-plugin-api
```

**Plugin Framework**: PF4J (Plugin Framework for Java) - lightweight, supports hot-reload, ClassLoader isolation.

---

## Module Responsibilities

| Module | Contains | Hot-reloadable? |
|--------|----------|-----------------|
| `bot-plugin-api` | Interfaces: `GamePlugin`, `GameMessageTypes`, abstract base messages | No (core dependency) |
| `bot-plugin-core` | Bot implementations: `BettingMiniGameBot`, `Bot` base class, game state, utilities | Yes |
| `bot-core` | Spring Boot app, REST API, BotFactory, PluginManager, BotGroupBehaviorService | No |
| `bom-plugin` | BOM messages: `BomSubscribeMessage`, `BomEndGameMessage`, `BomGameMessageTypes` | Yes |
| `nohu-plugin` | NOHU messages: `NohuSubscribeMessage`, `NohuEndGameMessage`, `NohuGameMessageTypes` | Yes |

---

## Phase 1: Maven Multi-Module Setup

### 1.1 New Project Structure

```
Bot/
├── pom.xml                         # Parent POM (aggregator)
│
├── bot-plugin-api/                 # Interfaces & base classes
│   ├── pom.xml
│   └── src/main/java/com/vingame/bot/plugin/api/
│       ├── GamePlugin.java         # PF4J extension point
│       ├── GameMessageTypes.java   # Message type provider interface
│       ├── BotPlugin.java          # Bot script provider interface
│       ├── PluginMetadata.java     # Plugin descriptor
│       └── message/                # Abstract base messages
│           ├── BettingMiniMessage.java
│           ├── SubscribeMessage.java
│           ├── StartGameMessage.java
│           ├── StartGameMd5Message.java
│           ├── UpdateBetMessage.java
│           └── EndGameMessage.java
│
├── bot-plugin-core/                # Bot script implementations
│   ├── pom.xml
│   └── src/main/java/com/vingame/bot/plugin/core/
│       ├── BotPluginCore.java      # PF4J Plugin class
│       ├── bot/
│       │   ├── Bot.java            # Abstract base bot
│       │   └── BettingMiniGameBot.java
│       └── util/
│           ├── GameState.java
│           ├── BettingMiniGameState.java
│           └── SessionIdStore.java
│
├── bot-core/                       # Main Spring Boot application
│   ├── pom.xml
│   └── src/main/java/com/vingame/bot/
│       ├── Starter.java
│       ├── config/
│       ├── domain/
│       │   ├── botgroup/
│       │   ├── environment/
│       │   ├── game/
│       │   └── plugin/             # NEW: Plugin management
│       │       ├── controller/
│       │       ├── service/
│       │       └── dto/
│       └── infrastructure/
│           ├── client/
│           ├── runtime/
│           └── plugin/             # NEW: PluginManagerService
│
├── plugins/
│   ├── bom-plugin/                 # BOM messages plugin
│   │   ├── pom.xml
│   │   └── src/main/java/com/vingame/bot/plugin/bom/
│   │       ├── BomPlugin.java
│   │       ├── BomGameMessageTypes.java
│   │       └── message/
│   │           ├── BomSubscribeMessage.java
│   │           ├── BomStartGameMessage.java
│   │           ├── BomStartGameMd5Message.java
│   │           ├── BomUpdateBetMessage.java
│   │           └── BomEndGameMessage.java
│   │
│   └── nohu-plugin/                # NOHU messages plugin
│       ├── pom.xml
│       └── src/main/java/com/vingame/bot/plugin/nohu/
│           └── ... (similar structure)
│
└── plugins-dist/                   # Runtime plugin JARs
    ├── bot-plugin-core-1.0.jar
    ├── bom-plugin-1.0.jar
    └── nohu-plugin-1.0.jar
```

### 1.2 Parent POM

```xml
<packaging>pom</packaging>
<modules>
    <module>bot-plugin-api</module>
    <module>bot-plugin-core</module>
    <module>bot-core</module>
    <module>plugins/bom-plugin</module>
    <module>plugins/nohu-plugin</module>
</modules>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
            <version>3.11.0</version>
        </dependency>
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j-spring</artifactId>
            <version>0.9.0</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## Phase 2: Plugin API Module

### 2.1 GamePlugin Extension Point

**New file:** `bot-plugin-api/.../api/GamePlugin.java`
```java
public interface GamePlugin extends ExtensionPoint {
    String getProductCode();           // e.g., "097", "118"
    String getPluginName();            // Human-readable name
    GameMessageTypes getMessageTypes(); // Message type provider
    PluginMetadata getMetadata();
}
```

### 2.2 BotPlugin Extension Point (for bot-plugin-core)

**New file:** `bot-plugin-api/.../api/BotPlugin.java`
```java
public interface BotPlugin extends ExtensionPoint {
    String getGameType();              // e.g., "BETTING_MINI"
    Bot createBot();                   // Factory method for bot instances
    PluginMetadata getMetadata();
}
```

### 2.3 Classes to Move to bot-plugin-api

| From (current location) | To |
|-------------------------|-----|
| `domain/bot/message/GameMessageTypes.java` | `bot-plugin-api/.../api/` |
| `domain/bot/message/BettingMiniMessage.java` | `bot-plugin-api/.../api/message/` |
| `domain/bot/message/SubscribeMessage.java` | `bot-plugin-api/.../api/message/` |
| `domain/bot/message/StartGameMessage.java` | `bot-plugin-api/.../api/message/` |
| `domain/bot/message/StartGameMd5Message.java` | `bot-plugin-api/.../api/message/` |
| `domain/bot/message/UpdateBetMessage.java` | `bot-plugin-api/.../api/message/` |
| `domain/bot/message/EndGameMessage.java` | `bot-plugin-api/.../api/message/` |

---

## Phase 3: Bot Plugin Core Module

### 3.1 Classes to Move to bot-plugin-core

| From (current location) | To |
|-------------------------|-----|
| `domain/bot/core/Bot.java` | `bot-plugin-core/.../core/bot/` |
| `domain/bot/core/BettingMiniGameBot.java` | `bot-plugin-core/.../core/bot/` |
| `domain/bot/util/GameState.java` | `bot-plugin-core/.../core/util/` |
| `domain/bot/util/BettingMiniGameState.java` | `bot-plugin-core/.../core/util/` |
| `domain/bot/util/SessionIdStore.java` | `bot-plugin-core/.../core/util/` |

### 3.2 BotPluginCore Implementation

**New file:** `bot-plugin-core/.../BotPluginCore.java`
```java
public class BotPluginCore extends Plugin {
    public BotPluginCore(PluginWrapper wrapper) {
        super(wrapper);
    }

    @Extension
    public static class BettingMiniBotPlugin implements BotPlugin {
        @Override
        public String getGameType() { return "BETTING_MINI"; }

        @Override
        public Bot createBot() { return new BettingMiniGameBot(); }

        @Override
        public PluginMetadata getMetadata() {
            return new PluginMetadata("bot-plugin-core", "1.0.0", ...);
        }
    }
}
```

---

## Phase 4: Game Message Plugins

### 4.1 BOM Plugin

**Move to:** `plugins/bom-plugin/.../`
- `BomGameMessageTypes.java`
- `BomSubscribeMessage.java`
- `BomStartGameMessage.java`
- `BomStartGameMd5Message.java`
- `BomUpdateBetMessage.java`
- `BomEndGameMessage.java`

**New file:** `plugins/bom-plugin/.../BomPlugin.java`
```java
public class BomPlugin extends Plugin {
    @Extension
    public static class BomGamePlugin implements GamePlugin {
        @Override
        public String getProductCode() { return "097"; }

        @Override
        public GameMessageTypes getMessageTypes() {
            return new BomGameMessageTypes();
        }
    }
}
```

### 4.2 NOHU Plugin (same structure)

---

## Phase 5: Plugin Infrastructure in Core

### 5.1 PluginManagerService

**New file:** `bot-core/.../infrastructure/plugin/PluginManagerService.java`

```java
@Service
public class PluginManagerService {
    private final SpringPluginManager pluginManager;
    private final Map<String, GamePlugin> gamePlugins = new ConcurrentHashMap<>();
    private final Map<String, BotPlugin> botPlugins = new ConcurrentHashMap<>();

    public Optional<GameMessageTypes> getMessageTypes(String productCode) {
        return Optional.ofNullable(gamePlugins.get(productCode))
                       .map(GamePlugin::getMessageTypes);
    }

    public Optional<Bot> createBot(String gameType) {
        return Optional.ofNullable(botPlugins.get(gameType))
                       .map(BotPlugin::createBot);
    }

    public void reloadPlugin(String pluginId) {
        pluginManager.unloadPlugin(pluginId);
        pluginManager.loadPlugin(pluginPath);
        pluginManager.startPlugin(pluginId);
        refreshCaches();
    }
}
```

### 5.2 Update GameMessageTypesResolver

**Modify:** `bot-core/.../domain/bot/message/GameMessageTypesResolver.java`

```java
@Component
public class GameMessageTypesResolver {
    private final PluginManagerService pluginManager;

    public GameMessageTypes resolve(ProductCode productCode) {
        return pluginManager.getMessageTypes(productCode.getCode())
            .orElseThrow(() -> new IllegalArgumentException(
                "No plugin for product: " + productCode));
    }
}
```

### 5.3 Update BotFactory

**Modify:** `bot-core/.../domain/bot/service/BotFactory.java`

```java
@Component
public class BotFactory {
    private final PluginManagerService pluginManager;
    private final GameMessageTypesResolver messageTypesResolver;

    public Bot createBot(String environmentId, BotConfiguration config) {
        Game game = config.getGame();

        // Get bot instance from plugin
        Bot bot = pluginManager.createBot(game.getGameType().name())
            .orElseThrow(() -> new IllegalArgumentException(
                "No bot plugin for game type: " + game.getGameType()));

        // Get message types from plugin
        GameMessageTypes messageTypes = messageTypesResolver.resolve(
            env.getProductCode());

        // Configure bot
        bot.setMessageTypes(messageTypes);
        bot.setClients(...);
        bot.initialize();

        return bot;
    }
}
```

---

## Phase 6: Seamless Reload Orchestration

### 6.1 Key Insight: Per-Group Cycling

**NOT this (batch):**
```
Stop Group 1 → Stop Group 2 → Stop Group 3 → Reload → Start 1 → Start 2 → Start 3
```

**YES this (seamless per-group):**
```
Reload plugin in memory
  │
  ├─► Group 1: Stop → Start (immediately) ─► Running with new code
  │
  ├─► Group 2: Stop → Start (immediately) ─► Running with new code
  │
  └─► Group 3: Stop → Start (immediately) ─► Running with new code
```

Each group experiences minimal downtime (just the restart time), and other groups continue running during the process.

### 6.2 PluginService Implementation

**New file:** `bot-core/.../domain/plugin/service/PluginService.java`

```java
@Service
public class PluginService {
    private final PluginManagerService pluginManager;
    private final BotGroupBehaviorService behaviorService;
    private final BotGroupService botGroupService;

    @Value("${plugin.reload.delay-between-groups-ms:2000}")
    private long delayBetweenGroups;

    public PluginReloadResult reloadPlugin(String pluginId) {
        PluginReloadResult result = new PluginReloadResult(pluginId);

        // 1. Reload plugin in memory (new classes loaded)
        pluginManager.reloadPlugin(pluginId);
        result.setPluginReloaded(true);

        // 2. Find affected bot groups
        String productCode = pluginManager.getProductCode(pluginId);
        List<BotGroup> affectedGroups = findGroupsByProductCode(productCode);

        // 3. Cycle each group: stop → start (seamless)
        for (BotGroup group : affectedGroups) {
            try {
                int groupId = Integer.parseInt(group.getId());

                // Stop group
                behaviorService.stop(groupId);
                result.addGroupStopped(group.getId());

                // Immediately restart with new plugin code
                behaviorService.start(groupId);
                result.addGroupStarted(group.getId());

                // Optional small delay before next group
                if (delayBetweenGroups > 0) {
                    Thread.sleep(delayBetweenGroups);
                }

            } catch (Exception e) {
                result.addGroupError(group.getId(), e.getMessage());
            }
        }

        return result;
    }
}
```

### 6.3 Reload Flow Diagram

```
POST /api/v1/plugins/bom-plugin/reload
  │
  ├─► 1. Reload plugin in PluginManager
  │      └── New BomGameMessageTypes loaded
  │
  ├─► 2. Find affected groups (product code = 097)
  │      └── Groups [1, 5, 12] use BOM
  │
  ├─► 3. Cycle Group 1:
  │      ├── Stop (30s graceful shutdown, bots disconnect)
  │      ├── Start (new bots created with new MessageTypes)
  │      └── Group 1 now running with new code
  │
  ├─► 4. Wait 2s (configurable)
  │
  ├─► 5. Cycle Group 5:
  │      ├── Stop → Start
  │      └── Group 5 now running with new code
  │
  ├─► 6. Wait 2s
  │
  └─► 7. Cycle Group 12:
         ├── Stop → Start
         └── Group 12 now running with new code

Result: All groups updated, each experienced ~30s downtime individually
        Other groups (3, 4, 7...) never stopped
```

---

## Phase 7: Plugin REST API

### 7.1 PluginController

**New file:** `bot-core/.../domain/plugin/controller/PluginController.java`

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/plugins` | List all loaded plugins |
| GET | `/api/v1/plugins/{id}` | Get plugin details |
| POST | `/api/v1/plugins/{id}/reload` | Reload and cycle affected groups |
| POST | `/api/v1/plugins/upload` | Upload new plugin JAR |
| DELETE | `/api/v1/plugins/{id}` | Unload plugin |
| GET | `/api/v1/plugins/{id}/affected-groups` | List groups using this plugin |

### 7.2 Response DTOs

```java
public record PluginDTO(
    String pluginId,
    String pluginName,
    String version,
    String productCode,
    PluginState state,
    int affectedGroupCount
) {}

public record PluginReloadResult(
    String pluginId,
    boolean pluginReloaded,
    List<GroupCycleStatus> groupStatuses,
    Instant completedAt
) {}

public record GroupCycleStatus(
    String groupId,
    boolean stopped,
    boolean started,
    long downtimeMs,
    String error
) {}
```

---

## Configuration

### application.properties

```properties
# Plugin directory
plugin.directory=plugins-dist

# Delay between cycling groups (ms)
plugin.reload.delay-between-groups-ms=2000

# PF4J mode
pf4j.mode=deployment
# pf4j.mode=development  # For dev - loads from classpath
```

---

## Files Summary

### Files to Create

| File | Purpose |
|------|---------|
| `bot-plugin-api/pom.xml` | API module |
| `bot-plugin-api/.../GamePlugin.java` | Game plugin extension point |
| `bot-plugin-api/.../BotPlugin.java` | Bot plugin extension point |
| `bot-plugin-api/.../PluginMetadata.java` | Plugin descriptor |
| `bot-plugin-core/pom.xml` | Bot scripts module |
| `bot-plugin-core/.../BotPluginCore.java` | PF4J plugin class |
| `bot-core/.../infrastructure/plugin/PluginManagerService.java` | Plugin management |
| `bot-core/.../domain/plugin/service/PluginService.java` | Reload orchestration |
| `bot-core/.../domain/plugin/controller/PluginController.java` | REST API |
| `bot-core/.../config/PluginConfig.java` | Spring config |
| `plugins/bom-plugin/pom.xml` | BOM plugin module |
| `plugins/bom-plugin/.../BomPlugin.java` | BOM plugin class |
| `plugins/nohu-plugin/pom.xml` | NOHU plugin module |
| `plugins/nohu-plugin/.../NohuPlugin.java` | NOHU plugin class |

### Files to Move

| From | To Module |
|------|-----------|
| `GameMessageTypes.java` | `bot-plugin-api` |
| `BettingMiniMessage.java` + abstract messages | `bot-plugin-api` |
| `Bot.java`, `BettingMiniGameBot.java` | `bot-plugin-core` |
| `GameState.java`, `SessionIdStore.java` | `bot-plugin-core` |
| `BomGameMessageTypes.java` + BOM messages | `bom-plugin` |
| `NohuGameMessageTypes.java` + NOHU messages | `nohu-plugin` |

### Files to Modify

| File | Change |
|------|--------|
| `pom.xml` | Convert to parent POM |
| `GameMessageTypesResolver.java` | Use plugin lookup |
| `BotFactory.java` | Use plugin for bot creation |

---

## Verification Plan

1. **Build**: `mvn clean install`
2. **Start**: `mvn spring-boot:run -pl bot-core`
3. **Check plugins**: `GET /api/v1/plugins` → should list bot-plugin-core, bom-plugin, nohu-plugin
4. **Start BOM bot group**: `POST /api/v1/bot-group/1/start`
5. **Verify bots connect** and process messages
6. **Test reload**:
   - Modify `BomEndGameMessage.java` in bom-plugin
   - `mvn package -pl plugins/bom-plugin`
   - Copy JAR to `plugins-dist/`
   - `POST /api/v1/plugins/bom-plugin/reload`
   - Watch logs: Group 1 stops → starts, other groups unaffected
7. **Verify new code active**: Check bot behavior reflects changes

---

## Implementation Order

1. **Week 1**: Multi-module setup, create bot-plugin-api
2. **Week 2**: Create bot-plugin-core, move bot classes
3. **Week 2-3**: Create bom-plugin, nohu-plugin, migrate messages
4. **Week 3**: Plugin infrastructure in bot-core
5. **Week 3-4**: Reload orchestration + REST API
6. **Week 4**: Testing, documentation
