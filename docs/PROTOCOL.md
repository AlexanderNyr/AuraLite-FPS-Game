# Wire protocol

One UDP socket carries everything: discovery, handshake, input, snapshots,
ping and lobby/match events. There is no TCP connection and no second port.

All integers are **big-endian**. The reference implementation is
`shared/src/main/kotlin/com/lanfps/shared/{Protocol,Packets}.kt` — this document
describes exactly what that code does.

---

## 1. Datagram framing

Every datagram, in both directions:

```
offset  size  field
------  ----  ------------------------------------------------------------
  0      4    magic            0x4C414E46  ("LANF")
  4      1    protocolVersion  currently 2
  5      1    packetType       see §2
  6      2    sequence         u16, wraps; sender-local
  8      2    ack              u16, newest sequence seen from the peer
 10      2    payloadLength    u16, bytes after the header
 12      4    checksum         CRC32 of the payload bytes only
 16     ...   payload
```

Header size: **16 bytes**. Maximum datagram: **10240 bytes**
(`GameConstants.MAX_PACKET_SIZE`). **Snapshots are additionally capped at
`GameConstants.SNAPSHOT_MAX_BYTES = 1400`**, under the ~1472-byte unfragmented
UDP MTU, so a snapshot can never fragment on Ethernet/Wi-Fi (a single lost
fragment would destroy the whole datagram).

A packet is **silently dropped** — never fatally — when any of these fail:

| Check | Result |
|---|---|
| length < 16 | `TOO_SHORT` |
| length > 10240 | `TOO_LARGE` |
| magic ≠ `LANF` | `BAD_MAGIC` |
| version ≠ 1 | `BAD_VERSION` |
| 16 + payloadLength > length | `BAD_LENGTH` |
| CRC32 mismatch | `BAD_CHECKSUM` |

Dropping rather than throwing is what makes it safe to expose the port on a LAN
full of stray broadcast traffic (mDNS, SSDP, DHCP…). After a successful parse
the reader is clamped to exactly `payloadLength` bytes, so a lying length field
cannot make a decoder read into neighbouring buffer contents.

### Primitive encodings

| Type | Encoding |
|---|---|
| `u8` / `i8` | 1 byte |
| `bool` | `u8`, 0 or 1 |
| `u16` / `i16` | 2 bytes, big-endian |
| `i32` | 4 bytes, big-endian |
| `i64` | 8 bytes, big-endian |
| `f32` | 4 bytes, IEEE-754 raw bits, big-endian |
| `string` | `u8` byte-length, then that many UTF-8 bytes (truncated on a safe boundary, max 255) |

---

## 2. Packet types

| id | Name | Direction |
|---:|---|---|
| 1 | `DISCOVERY_REQUEST` | client → broadcast |
| 2 | `DISCOVERY_RESPONSE` | server → client |
| 3 | `CONNECT_REQUEST` | client → server |
| 4 | `CONNECT_ACCEPTED` | server → client |
| 5 | `CONNECT_REJECTED` | server → client |
| 6 | `CLIENT_INPUT` | client → server |
| 7 | `SERVER_SNAPSHOT` | server → client |
| 8 | `PING` | either |
| 9 | `PONG` | either |
| 10 | `DISCONNECT` | either |
| 11 | `LOBBY_STATE` | server → client |
| 12 | `MATCH_EVENT` | server → client |

---

## 3. Payloads

### 3.1 `DISCOVERY_REQUEST` (1)

```
string clientTag        e.g. "android"
```

Broadcast to `255.255.255.255` and to every interface's directed broadcast
address, on the game port.

### 3.2 `DISCOVERY_RESPONSE` (2)

```
string serverName
string arena
u8     mode            0 = DM, 1 = TDM
u8     playerCount     humans only
u8     maxPlayers
u8     botCount
u16    udpPort
```

### 3.3 `CONNECT_REQUEST` (3)

```
string nickname         <= 16 characters
u8     preferredMode    advisory only; the server decides
i64    clientTimeMs
i32    resumeToken      0 = new connection, else resume this session
```

`resumeToken` is the token the server handed out in `CONNECT_ACCEPTED`. A
client that dropped out silently can reconnect on a **new socket** and, by
presenting this token, get back the *same* entity, score and team instead of a
fresh session (P0-2). A token with no matching live/zombie session is treated as
a brand-new connection.

`preferredMode` is **ignored**. The ruleset is owned by whoever runs the server
(`server.properties` / `run-server.bat --mode=TDM`); a connecting client can
never change it. The current client always sends `DM` here, so honouring the
field would silently turn every TDM server into a deathmatch — and reset the
scores — the moment the first phone joined. The field stays on the wire so the
packet layout is stable and a future "vote for mode" feature has somewhere to
live. Read the authoritative mode from `CONNECT_ACCEPTED.mode` and from every
snapshot's `mode` field.

### 3.4 `CONNECT_ACCEPTED` (4)

```
u16    playerId         the client's entity id in every snapshot
u8     team             0 = NONE, 1 = RED, 2 = BLUE
u8     mode
string arena
u8     tickRate         60
u8     snapshotRate     30
i64    serverTimeMs
string assignedNickname may differ (de-duplicated / sanitised)
i32    arenaHash        FNV-1a fingerprint of the geometry
i32    resumeToken      opaque token to present on a reconnect (0 = none)
```

The client compares `arenaHash` with its own `ArenaDef.hash()`. A mismatch is a
**warning**, not a disconnect: play continues but the HUD shows
`! map mismatch with server !`, because differing collision geometry makes
prediction fight the server near walls.

The client stores `resumeToken` and sends it back in `CONNECT_REQUEST` if the
link drops, so the server can resume the session (P0-2).

### 3.5 `CONNECT_REJECTED` (5) / `DISCONNECT` (10)

```
string reason
```

### 3.6 `CLIENT_INPUT` (6)

```
u16    playerId
u16    reportedPingMs   client's own RTT estimate; DISPLAY ONLY, never trusted
u8     commandCount
       commandCount x InputCommand
```

`InputCommand` (24 bytes):

```
u16    sequence         wraps at 16 bits
i32    clientTimeMs     low 32 bits of the client clock; diagnostics only
f32    moveForward      -1..1
f32    moveRight        -1..1
f32    yaw              degrees, absolute
f32    pitch            degrees, absolute, clamped to +/-89
u8     buttons          bit 0 FIRE, bit 1 JUMP, bit 2 CROUCH, bit 3 RELOAD
u8     weapon
```

Two deliberate design decisions live here:

1. **No delta-time on the wire.** Every command is worth exactly one fixed
   `1/60 s` step. A client cannot lie about `dt` to move faster, and prediction
   is bit-comparable with the server simulation.
2. **Redundancy.** Each packet repeats the previous
   `GameConstants.INPUT_REDUNDANCY = 3` commands. A single delivered packet
   heals a gap, which removes the need for any ack/retransmit machinery. The
   server de-duplicates by sequence.

The server additionally rate-limits to `MAX_INPUTS_PER_SECOND = 90` commands per
second per session (token bucket), so a modified client cannot buy extra
movement by spamming input.

**Connection flood protection (P0-3).** New sessions are rate-limited *before*
any slot or CPU is spent: at most `MAX_CONNECTS_PER_SECOND = 5` brand-new
connections per second globally and `MAX_CONNECTS_PER_IP_SECOND = 2` per source
IP, plus a hard cap of `MAX_SESSIONS_PER_IP = 2` simultaneous active sessions
per source IP. Excess attempts are answered with `CONNECT_REJECTED`.

### 3.7 `SERVER_SNAPSHOT` (7)

```
i32    serverTick
i64    serverTimeMs
u16    lastProcessedInputSeq    <-- PER RECIPIENT
u8     mode
u8     matchState               0 WARMUP, 1 ACTIVE, 2 ENDED
f32    matchTimeRemaining       seconds
u16    redScore
u16    blueScore
u8     entityCount
       entityCount x EntityState
```

`EntityState`:

```
u16    id
u8     type            0 = PLAYER, 1 = BOT
u8     team
u8     flags           bit 0 ALIVE, bit 1 FIRING, bit 2 CROUCH
f32    x, y, z         feet position
f32    yaw, pitch      degrees
i16    vx, vy, vz      velocity quantised to 1/100 m/s
u8     health          0..100
u16    kills
u16    deaths
```

**No nickname in snapshots (P0-1).** Sending the name with every entity in every
30 Hz snapshot could push a full server past the UDP MTU — especially with
2-byte-per-character names (Cyrillic, CJK) — and IP fragmentation destroys a
whole snapshot when any fragment is lost. Names travel once per `LOBBY_STATE`
(§3.9) and the client joins them to entities by `id` via a roster.

Velocity is quantised because it is only used for dead reckoning and debug
display; three `i16`s save 6 bytes per entity versus three `f32`s, and 1 cm/s is
far below the noise floor of a 30 Hz snapshot.

**Per-recipient patching.** The entity list is identical for everyone, so the
server serialises the snapshot **once**, then for each client overwrites the
`lastProcessedInputSeq` field at byte offset

```
Packets.SNAPSHOT_LAST_INPUT_OFFSET = 16 (header) + 4 (tick) + 8 (time) = 28
```

and recomputes the CRC32 (`Protocol.rechecksum`). One serialisation, N cheap
patches.

### 3.8 `PING` (8) / `PONG` (9)

```
PING:  i64 clientTimeMs
PONG:  i64 clientTimeMs   (echoed unchanged)
       i64 serverTimeMs
```

RTT = `now - clientTimeMs`, smoothed with a 0.25 EMA on the client. Either side
may initiate; the client pings once per second.

### 3.9 `LOBBY_STATE` (11)

```
string serverName
string arena
u8     mode
u8     matchState
u8     botCount
u8     maxPlayers
f32    matchTimeRemaining
u8     playerCount
       playerCount x {
           u16    id
           string name
           u8     team
           bool   bot
           u16    kills
           u16    deaths
           u16    pingMs
       }
```

### 3.10 `MATCH_EVENT` (12)

```
u8     eventType    1 KILL, 2 MATCH_START, 3 MATCH_END, 4 PLAYER_JOINED, 5 PLAYER_LEFT
u16    killerId
u16    victimId
string killerName
string victimName
i32    extra        winning team for MATCH_END
```

---

## 4. Session lifecycle

```
client                                server
  |                                     |
  |------ DISCOVERY_REQUEST (bcast) --->|   (optional)
  |<----- DISCOVERY_RESPONSE -----------|
  |                                     |
  |------ CONNECT_REQUEST ------------->|   retried up to 16x, 300 ms apart
  |<----- CONNECT_ACCEPTED -------------|   (or CONNECT_REJECTED)
  |                                     |
  |==== CLIENT_INPUT @ 60 Hz =========>>|   3 commands per packet
  |<<=== SERVER_SNAPSHOT @ 30 Hz =======|
  |------ PING @ 1 Hz ----------------->|
  |<----- PONG ------------------------ |
  |<----- MATCH_EVENT (as they happen) -|
  |                                     |
  |------ DISCONNECT ------------------>|   courtesy; frees the slot at once
```

Timeouts:

| Constant | Value | Meaning |
|---|---|---|
| `CLIENT_TIMEOUT_MS` | 5000 | client considers the link silent and starts reconnecting |
| `RECONNECT_TIMEOUT_MS` | 30000 | client gives up if no accept arrives within this window |
| `SERVER_TIMEOUT_MS` | 8000 | server marks a silent session a **zombie** (kept for reconnect) |
| `ZOMBIE_TIMEOUT_MS` | 30000 | server reclaims a zombie session if it never reconnects |

**Reconnect (P0-2).** When a client goes silent the server does **not** drop it
immediately. It marks the session a *zombie*: the entity stays in the world
(standing still, still shootable, score/team preserved) and the slot is held for
`ZOMBIE_TIMEOUT_MS`. If the client returns within that window presenting its
`resumeToken` — on the same or a new socket — it is re-bound to the same entity.
Only when the zombie window expires is the entity removed and the slot freed.

---

## 5. Authority model

The server is the **only** source of truth.

- The client sends **intent** (stick, look angles, buttons). It never sends
  "I hit X", a position, or a damage number.
- Hitscan is resolved exclusively on the server
  (`server/Raycast.kt` → `ServerRaycast`), against the server's own entity
  positions, using the same `shared/RayMath.kt` the bots use for line of sight.
  That is why nothing can shoot through a wall.
- The client *does* fire a local ray, but only to decide where to stop drawing a
  tracer. It has no effect on the game.
- Health, kills, deaths, scores, respawns and match state exist only in the
  server's `World` and reach the client as snapshot fields.
- View angles are the one thing the client owns, because aim must feel instant.
  They are clamped and sanitised server-side (`InputCommand.sanitize()`).

## 6. Latency handling

| Mechanism | Where | Purpose |
|---|---|---|
| Client prediction | `client/Prediction.kt` | local player reacts with 0 ms input lag |
| Server reconciliation | same | authoritative correction, replaying unacked input |
| Error smoothing | same | corrections under 1.5 m fade over ~120 ms instead of snapping |
| Entity interpolation | `client/SnapshotBuffer.kt` | remote players render 90 ms in the past, always between two known states |
| Input redundancy | `Packets.writeClientInput` | survives packet loss with no retransmits |
| Snapshot freeze | `SnapshotBuffer.sampleInto` | never extrapolates past the newest snapshot |

Prediction and the server run the **same** `MovementSolver` from `:shared` over
the same fixed timestep. When no packets are lost, replaying the unacknowledged
commands reproduces the predicted state exactly, so a correctly predicted frame
requires no correction at all — this is verified by
`client-android/src/test/.../ClientLogicTest.kt`.

---

## 7. Adding a packet type

1. Add the id to `PacketTypes`.
2. Add `writeX` / `readX` to `Packets`.
3. Handle it in `server/GameServer.kt` and/or `client/NetworkClient.kt`.
4. Bump `GameConstants.PROTOCOL_VERSION` if an existing layout changed — the
   version check then rejects mismatched builds cleanly instead of letting them
   misparse each other.
