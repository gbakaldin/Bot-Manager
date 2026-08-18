# TIP Connection Trace — frame-level drop characterization

Date: 2026-08-05
Host: Bot-1 (staging), container `bot-java-bot-manager-1` (Up ~1h, healthy)
Type: **read-only diagnostic** — no build, no ship, no compose change, no commit. Prod untouched.

## Method

- Confirmed target group `40fa3749-8c36-4cd6-9943-f86e5ed287be` ("XD game test", Xóc Đĩa, P_116/TIP)
  via `GET /api/v1/bot-group/40fa3749…/health`.
- Raised `com.vingame.bot`→TRACE and `com.vingame.websocketparser`→TRACE via actuator on `:8080`
  (both logger names verified to resolve first). Held TRACE for **75 s**, snapshotted
  `docker logs --since` window (1,066,089 lines / 127 MB), then **immediately restored**
  `com.vingame.bot`→DEBUG, `com.vingame.websocketparser`→INFO.
- **Log levels restored and verified** at end: `bot={configuredLevel:DEBUG}`,
  `websocketparser={configuredLevel:INFO}`. Server temp captures deleted. TRACE was on for ~75 s only.

## Health snapshot at start

Group `40fa3749` status **DEAD**, consecutiveFailures=16, totalBots=20,
connectedBots=4, deadBots=16. The 4 connected: `xdt3st22`, `xdt3st29`, `xdt3st212`,
`xdt3st213` (all `CONNECTION_AUTHENTICATED`, thousands of bets placed). The other 16
(incl. `xdt3st21`) `DEAD`, 0 bets.

---

## IMPORTANT — what the 75 s window actually contained

The task premise was that TIP `xdt3st2*` bots reconnect ~every 60 s and drop 1–6 ms after
handshake. **In this window that is NOT what the failed TIP bots do.** Two distinct facts:

1. **The 16 DEAD TIP bots (incl. `xdt3st21`) are dormant — NOT reconnecting.**
   Every one of the 4,117 in-window lines for `xdt3st21` is the same `ws-ping-0`
   `WARN … Cannot send message, not connected`. **Zero** handshake / AUTH / `Resolving
   authentication` / `Channel became inactive` events for *any* `xdt3st2*` bot appear in the
   window. The group is DEAD (16 consecutive failures) and its failed bots have stopped the
   reconnect loop entirely — they just spin the 5 s ping scheduler against a dead channel.
   So there was **no fresh TIP-bot drop loop to capture**.

2. **A different, concurrent group reproduces the exact described symptom.**
   Group `8a4b9f60-b342-4c6f-9dff-11023fcd215a` (**P_097/BOM**, `BETTING_MINI`, game
   "MiniGame3", auth `https://apigw-bomwin.sgame.us/gwms/v1/bot/login.aspx`, WS endpoint
   **`bomwsk-gpg.sgame.club`** behind Cloudflare `172.67.162.223` / `104.21.82.204`) has bot
   `authtestws97110` churning: connect → AUTH → **dropped 3–6 ms after handshake** →
   reconnect with backoff. This is the loop reconstructed below. It is a **BOM** endpoint,
   not the TIP endpoint, but it is the only live connect→drop loop in the window and it
   characterizes the server-side drop behaviour precisely.

The healthy contrast loop below **is** from the TIP group `40fa3749` (held bot `xdt3st22`),
so "held TIP" vs "dropped (BOM)" still sit side by side, and both the drop mechanics and a
working bidirectional session are captured.

---

## DROPPED loop — one complete cycle (`ws-authtestws97110`, group `8a4b9f60`, BOM)

Cycle at 11:46:54 (representative; all 7 cycles in the window are identical to the ms).

```
11:46:54.567 [reconnect-authtestws97110] Bot: AUTHENTICATED → CONNECTING
11:46:54.567 [reconnect-authtestws97110] Client ws-authtestws97110: Resolving authentication tokens...
11:46:54.567 [reconnect-authtestws97110] Client ws-authtestws97110: Authenticated with token 29-2b39c6...
11:46:54.600 [multiThreadIoEventLoopGroup-2-4] Client ws-authtestws97110: WebSocket handshake completed
11:46:54.600 [reconnect-authtestws97110]      Client ws-authtestws97110: Connected to server
11:46:54.600 [multiThreadIoEventLoopGroup-2-4] Bot: CONNECTING → CONNECTED
11:46:54.600 [reconnect-authtestws97110]      Bot starting. Client: 1276279023
11:46:54.600 [reconnect-authtestws97110]      checkBalance() cached 1000000000 ; session balance 1000000000
11:46:54.600 [multiThreadIoEventLoopGroup-2-4] Bot: CONNECTED → AUTHENTICATING_CONNECTION
11:46:54.600 [reconnect-authtestws97110]      Bot: AUTHENTICATING_CONNECTION → STARTED
     --- AUTH frame SENT (same 54.600 ms tick, IO thread) ---
     AUTH [1,"MiniGame3","","",{"accessToken":"29-2b39c680b2d9e7f4c8782770b713addd","agentId":"1","reconnect":false}]
11:46:54.604 [multiThreadIoEventLoopGroup-2-4] Client ws-authtestws97110: Channel became inactive
                                                (remote address: bomwsk-gpg.sgame.club/172.67.162.223:443)
11:46:54.604 [multiThreadIoEventLoopGroup-2-4] Client ws-authtestws97110: Connection closed
                                                (was connected: true, channel active: false)
11:46:57.600 [reconnect-authtestws97110] Bot: reconnect attempt 1 did not hold   (+3.0 s hold-check)
   ... backoff wait ...
11:47:07.600 [reconnect-authtestws97110] Bot: STARTED → CONNECTING   (next attempt)
```

### The crux — did the server send ANY bytes before dropping?

**No. It is a silent close.** Between "AUTH message sent" (11:46:54.600) and "Channel became
inactive" (11:46:54.604) there is **nothing inbound**:

- No auth ack `[1,true,…]`.
- No game data `[5,{…}]`.
- No WebSocket close frame carrying a code/reason — the library logs only its own local
  `Connection closed (was connected: true, channel active: false)` and a stack-origin
  `Connection closed from:` line; there is no server-supplied close code or reason text.
- Grep of the **entire** window for `authtestws97110` inbound indicators
  (`RECEIVED`, `[1,true`, `[5,`, `onMessage`, `onStartGame`) = **0 matches**, with
  `com.vingame.websocketparser` at TRACE (so any received frame would have been logged).

The channel completes the WS handshake, the client emits AUTH, and the peer (Cloudflare edge /
origin) tears the TCP/WS connection down **3–6 ms later without transmitting a single frame**.

### AUTH-sent → channel-inactive delta

| Cycle (handshake ts) | handshake completed | channel inactive | Δ |
|---|---|---|---|
| 11:44:40 | .434 | .438 | **4 ms** |
| 11:45:43 | .469 | .473 | **4 ms** |
| 11:46:46 | .500 | .504 | **4 ms** |
| 11:46:54 | .600 | .604 | **4 ms** |
| 11:47:07 | .633 | .637 | **4 ms** |
| 11:47:40 | .671 | .674 | **3 ms** |
| 11:48:43 | .707 | .713 | **6 ms** |

AUTH is emitted on the IO thread in the same millisecond as "handshake completed", so
**AUTH-sent → drop ≈ 3–6 ms** (median 4 ms). Matches the reported 1–6 ms symptom exactly.

### Reconnect / backoff behaviour

After each drop the client waits **~3 s** to declare `reconnect attempt N did not hold`, then
backs off before the next attempt. Observed inter-attempt gaps escalated **~10 s → 30 s → 60 s
(cap)**. After **7** consecutive "did not hold", the bot **re-authenticates** (fresh login →
new agency token `29-…`, `AUTHENTICATING → AUTHENTICATED`) and resets the attempt counter,
then resumes the same 10/30/60 s ladder. Token before re-auth: `29-9858171a…`; after:
`29-2b39c680…` — i.e. re-auth does fetch a new token, and the new token drops identically, so
the drop is **not** a stale-credential problem.

---

## HELD loop — contrast (`xdt3st22`, TIP group `40fa3749`, Xóc Đĩa)

Same TIP group under investigation; this bot authenticated before the window and is healthy.
It streams bidirectionally — sends `cmd:3002` bets and **receives `[5,{…}]` UpdateBet frames**
every second (crowd `bs` array with per-option `bc`/`b`/`v`), round `sid:3175212`, `gid:123`,
plugin `shakeDiskPlugin`:

```
11:48:13.818 [SENT]     ["6","MiniGame","shakeDiskPlugin",{"cmd":3002,"aid":1,"b":41000,"eid":1,"sid":3175212}]
11:48:13.830 [RECEIVED] [5,{"bs":[{"eid":5,"bc":15,"b":1545000,"v":4015000}, ... ],"gid":123,"cmd":3002}]
11:48:14.818 [SENT]     ["6","MiniGame","shakeDiskPlugin",{"cmd":3002,"aid":1,"b":16000,"eid":1,"sid":3175212}]
11:48:14.830 [RECEIVED] [5,{"bs":[ ... {"eid":1,"bc":9,"b":674000,"v":2259000} ... ],"gid":123,"cmd":3002}]
   ... continues every ~1 s: SENT bet → RECEIVED [5,…] server update ...
```

Side by side: the **held** channel gets a steady inbound `[5,…]` server stream; the **dropped**
channel gets **nothing** — it is severed 4 ms after handshake, before a single byte returns.

---

## Read / conclusion

- **The drop is a silent close.** At the frame level the server (or the Cloudflare edge in
  front of it) sends **zero bytes** after the WS handshake — no auth ack, no data, no close
  frame with a code/reason. It simply resets the connection **3–6 ms** after the client emits
  AUTH. From the app's view: `handshake completed` → `Connected` → `CONNECTING→CONNECTED→
  AUTHENTICATING_CONNECTION→STARTED` → `Channel became inactive` / `Connection closed`, with
  nothing in between. The 4 ms timing (far below any round-trip to an origin that would parse
  AUTH) is consistent with an **edge-level immediate reset** — the peer accepts the WS upgrade
  then drops without ever engaging the game server's auth handler.

- **Caveat on scope:** the reproduced drop loop is on the **BOM** endpoint
  `bomwsk-gpg.sgame.club` (group `8a4b9f60`), *not* the TIP endpoint. The TIP group `40fa3749`
  could not be observed dropping in-window because its 16 failed bots have **stopped
  reconnecting** (group DEAD, 16 consecutive failures — the reconnect loop appears to give up
  once the health monitor marks the group DEAD), while its 4 survivors stay authenticated. So
  this trace **proves the silent-close mechanics on a live churning BOM bot**, and shows the
  TIP group in a bimodal steady state (4 held + streaming, 16 dormant/ping-spinning). To
  capture a *fresh TIP* drop loop, the TIP group needs to be restarted so its bots re-enter the
  connect→drop cycle while TRACE is on. (Compare with the known staging note that Bot-1 cannot
  resolve `tipclubgw-sock.stgame.win` — but note the held TIP bots here are connected and
  streaming, so at least this TIP group's WS path is currently reachable for the survivors.)

- **Side observation (pre-existing bug, not introduced here):** once dropped and the group is
  DEAD, the 5 s ping scheduler keeps firing `Cannot send message, not connected` indefinitely
  (~73k/min fleet-wide) with no reconnect — matches the "no reconnection logic / tight WARN
  loop" and thread-leak sawtooth notes in CLAUDE.md / MEMORY.

## Log-level restoration

- `com.vingame.bot`: TRACE → **DEBUG** (verified `configuredLevel:DEBUG`).
- `com.vingame.websocketparser`: TRACE → **INFO** (verified `configuredLevel:INFO`).
- TRACE was active ~75 s only. Server-side capture files removed. No other state changed.
