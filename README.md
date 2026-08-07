# LAN FPS

A small multiplayer first-person shooter for a local network: an **authoritative
headless server** that runs on a Windows 10 PC and an **Android client** that
renders with raw OpenGL ES 3.2.

Everything here is written from scratch in Kotlin. There is **no game engine** —
no Unity, no Unreal, no Godot, no libGDX, no jMonkeyEngine, no Bevy. Rendering is
hand-written GLSL ES 3.00 and `GLES30` calls, networking is plain
`java.net.DatagramSocket`, physics and collision are a few hundred lines of AABB
sweeping in the `shared` module. The game needs **no internet connection** to
play: one Wi-Fi router (or hotspot) and nothing else.

```
   ┌──────────────────────────┐            UDP 7777            ┌─────────────┐
   │  Windows 10 PC           │◀──── input ────────────────────│  Phone 1    │
   │  java -jar server.jar    │────── snapshots (30 Hz) ───────▶│  (Android)  │
   │  authoritative @ 60 Hz   │                                └─────────────┘
   │  + 4 bots                │◀──── input ────────────────────┌─────────────┐
   │  binds 0.0.0.0:7777      │────── snapshots (30 Hz) ───────▶│  Phone 2    │
   └──────────────────────────┘                                └─────────────┘
```

---

## 1. What you get

| Deliverable | Path | Notes |
|---|---|---|
| Windows server bundle | `release/server.zip` | jar + `run-server.bat` + `server.properties` + `arena01.json` + Windows README |
| Signed Android APK | `release/lanfps-client-release.apk` | install this one on the phones |
| Debug Android APK | `release/lanfps-client-debug.apk` | same game, debuggable, larger |
| Wire protocol spec | `docs/PROTOCOL.md` | every byte of every packet |
| Two-phone test plan | `docs/LAN_TEST_CHECKLIST.md` | 12 steps, run this first |
| Fault finding | `docs/TROUBLESHOOTING.md` | firewall, IP, rubber-banding, build issues |
| Audit + roadmap | `docs/IMPROVEMENT_PLAN.md` | known weaknesses, prioritised, with effort estimates |

> Binaries are **not** committed to the repository — `release/` is gitignored.
> Attach `server.zip` and the APK to a GitHub Release, or grab them from the CI
> artifacts of any green build.

---

## 2. Quick start (the 5-minute version)

**On the Windows 10 PC**

1. Install a Java 17+ runtime — [Adoptium Temurin 17](https://adoptium.net/temurin/releases/?version=17).
2. Unzip `release/server.zip` anywhere, e.g. `C:\lanfps-server\`.
3. Double-click **`run-server.bat`**. Windows will ask about the firewall —
   tick **Private networks** and click *Allow access*.
4. The window prints the PC's IPv4 addresses. Note the `192.168.x.x` one.

```
[INFO] LAN FPS Server  protocolVersion=1
[INFO] listening on 0.0.0.0:7777
[INFO] arena=arena01  brushes=24  hash=0x01895281
[INFO] bots spawned: 4
[INFO] nav graph: 16 nodes, 60 links, 0 isolated
```

**On each phone**

5. Copy `lanfps-client-release.apk` over, tap it, allow *install from unknown
   sources*.
6. Join the **same Wi-Fi** as the PC.
7. Open **LAN FPS**, type the PC's IP (default `192.168.1.25`), port `7777`,
   pick a nickname, tap **CONNECT** — or tap **SCAN** to find the server by
   UDP broadcast.
8. Tap **ENTER MATCH**.

If step 7 fails, that is almost always the Windows firewall. See
[§8](#8-firewall-the-one-thing-that-always-breaks) and `docs/TROUBLESHOOTING.md`.

---

## 3. Controls

| Control | Action |
|---|---|
| Left half of the screen | Floating stick — walk / strafe |
| Right half of the screen | Drag to look (sensitivity + invert-Y in the menu) |
| **FIRE** button (bottom right) | Shoot — hold for automatic fire (8 rounds/s) |
| **JUMP** button | Jump |
| **CROUCH** button | Toggle crouch (slower, smaller, harder to hit) |
| **TAB** button | Hold to show the scoreboard |
| **☰** (top left) | Pause / disconnect |

The HUD shows a dynamic crosshair (it opens up when you move or fire), health,
ammo (`∞`), the match timer, the score, your ping, a kill feed and floating name
plates over the other players.

---

## 4. Project layout

```
lanfps/
├── settings.gradle.kts        plugin versions; :client-android is only included
│                              when an Android SDK is configured
├── build.gradle.kts           no plugins{} block on purpose — see TROUBLESHOOTING §12
├── gradle.properties
│
├── shared/                    pure-JVM code used by BOTH sides
│   └── src/main/kotlin/com/lanfps/shared/
│       ├── Protocol.kt        magic 0x4C414E46 "LANF", 16-byte header, CRC32
│       ├── Packets.kt         encode/decode for all 12 packet types
│       ├── PacketTypes.kt     DISCOVERY / CONNECT / INPUT / SNAPSHOT / PING / ...
│       ├── InputCommand.kt    24-byte client command + sanitise()
│       ├── EntityState.kt     quantised entity wire format
│       ├── Snapshot.kt        one server frame
│       ├── Movement.kt        MovementSolver — THE shared physics step
│       ├── RayMath.kt         ray/AABB + ray/capsule intersection
│       ├── ArenaDef.kt        the map, as data (24 brushes, 8 spawns, 16 waypoints)
│       ├── GameConstants.kt   every tunable number, one place
│       └── BinaryReader/Writer, Checksum, MiniJson, MathTypes, Team, GameMode
│
├── server/                    headless console app (Windows 10 / any JVM)
│   ├── src/main/kotlin/com/lanfps/server/
│   │   ├── Main.kt            arg parsing, banner, shutdown hook
│   │   ├── GameServer.kt      socket + 60 Hz tick loop + 30 Hz snapshot loop
│   │   ├── UdpServerSocket.kt receive/send, CRC validation, rate limits
│   │   ├── ClientSession.kt   per-client state, input queue, ping, timeout
│   │   ├── World.kt           entity registry, spawn selection
│   │   ├── Physics.kt         drives the shared MovementSolver
│   │   ├── Raycast.kt         hit detection — the ONLY place damage is decided
│   │   ├── BotAI.kt/BotEntity PATROL → SEEK → ATTACK → EVADE → RESPAWN_WAIT
│   │   ├── MatchController.kt DM / TDM rules, timer, kill limit, end-of-match
│   │   ├── ScoreSystem.kt     kills, deaths, team scores
│   │   ├── SnapshotBuilder.kt packs a snapshot per client
│   │   └── tools/TestClient.kt headless protocol client — the best LAN diagnostic
│   ├── src/main/resources/    server.properties, arena01.json
│   ├── run-server.bat
│   └── README_SERVER_WINDOWS.txt
│
├── client-android/            Android app, OpenGL ES 3.2, plain Views (no Compose)
│   └── src/main/kotlin/com/lanfps/client/
│       ├── MainActivity.kt    phase machine MENU → CONNECTING → LOBBY → PLAYING → ENDED
│       ├── NetworkClient.kt   rx/tx threads, handshake, 60 Hz input, 1 Hz ping
│       ├── Prediction.kt      local prediction + reconciliation + error smoothing
│       ├── SnapshotBuffer.kt  90 ms interpolation buffer, clock-offset tracking
│       ├── GameRenderer.kt    arena, players, view model, tracers, GLSL inline
│       ├── ShaderProgram/Mesh/MeshBuilder/Camera/GlUtil   the tiny "engine"
│       ├── TouchControlsView  multi-touch stick + look + buttons
│       ├── HudView.kt         crosshair, health, timer, kill feed, name plates
│       └── MenuView / LobbyView / ScoreboardView / EndMatchView / UiKit
│
├── scripts/                   build-release.sh | .bat, package-server.ps1
├── docs/                      PROTOCOL.md, LAN_TEST_CHECKLIST.md, TROUBLESHOOTING.md
├── keystore/lanfps.keystore   local signing key (password: lanfps)
└── release/                   ← the deliverables land here
```

---

## 5. How it works

### Authoritative server, thin client

The server owns the world. The client **never** tells the server "I hit
someone" — it only ever sends `CLIENT_INPUT` (stick axes, view angles, buttons).
The server runs the raycast in `Raycast.kt`, applies damage, and reports the
result in the next snapshot. That is the whole anti-cheat model, and it is why
the shot you see on your phone is the shot everyone else sees.

* **Tick rate** 60 Hz (`TICK_DT = 1/60`) — fixed step, deterministic.
* **Snapshot rate** 30 Hz — halves the bandwidth with no visible cost.
* Inputs are sent **3× redundantly**; the server de-duplicates by sequence
  number, so a single lost datagram never costs you a frame of movement.

### Prediction for you, interpolation for everyone else

Two different problems need two different fixes:

* **Your own player** is simulated locally the instant you touch the stick, using
  the *same* `MovementSolver` the server runs. Each snapshot carries
  `lastProcessedInputSeq`; the client drops the acknowledged commands, snaps onto
  the authoritative state and replays the unacknowledged tail. Because both sides
  run identical code over identical inputs, the replay normally lands exactly
  where you already were and nothing moves. When it doesn't, the difference is
  folded into a decaying offset (≈120 ms, clamped to 0.5 m) instead of
  teleporting the camera. A disagreement over `HARD_SNAP_METERS = 1.5` (respawn,
  teleport) is cut honestly rather than smoothed.
* **Everyone else** is drawn 90 ms in the past (`INTERPOLATION_DELAY_MS`) so
  there are always two snapshots to interpolate between. Past the newest
  snapshot the buffer **freezes** rather than extrapolating — a frozen enemy is
  annoying, an enemy extrapolated through a wall is unplayable. Out-of-order
  packets are re-sorted, duplicates dropped, and late arrivals are excluded from
  the clock-offset estimate so one delayed datagram cannot rewind the timeline.

### The map

`arena01` is an original low-poly arena — a symmetric 60 × 40 m indoor space with
a centre pillar, two lane dividers, crates and a raised catwalk, RED spawns west
and BLUE spawns east. It is stored as data (`arena01.json`, 24 brushes, 8 spawns,
16 bot waypoints) and shipped in **both** the jar and the APK's assets. Both
sides hash it (`0x01895281`) and the HUD warns if they disagree. No copyrighted
assets are used anywhere: every texture is a shader, every icon is a vector.

### Bots

Bots fill the server so the game is playable with one phone. They run a small
state machine — `PATROL` along the waypoint graph → `SEEK` a heard/seen enemy →
`ATTACK` with human-ish turn rate and aim error → `EVADE` at low health →
`RESPAWN_WAIT`. Difficulty (`botDifficulty`, 0.0–1.0) scales turn speed and aim
cone. They obey exactly the same physics and raycast rules as human players.

---

## 6. Running the server

`run-server.bat` accepts every option `server.properties` does; the command line
wins:

```bat
run-server.bat --mode=TDM --botCount=6
run-server.bat --udpPort=7778           REM if 7777 is taken
run-server.bat --logLevel=DEBUG
run-server.bat --selfTestSeconds=10     REM headless smoke test, no clients needed
```

| Option | Default | Meaning |
|---|---|---|
| `--udpPort` | `7777` | UDP port for *all* traffic |
| `--bindAddress` | `0.0.0.0` | leave as-is; `0.0.0.0` = all interfaces |
| `--mode` | `DM` | `DM` deathmatch, `TDM` red vs blue |
| `--botCount` | `4` | 0–16 AI opponents |
| `--maxPlayers` | `8` | human slots |
| `--matchTimeSeconds` | `300` | match length |
| `--killLimit` | `20` | early-win score |
| `--botDifficulty` | `0.55` | 0.0 easy → 1.0 hard |
| `--enableDiscovery` | `true` | answer LAN broadcast (SCAN button) |
| `--logLevel` | `INFO` | `DEBUG` / `INFO` / `WARN` / `ERROR` |

Stop it with `Ctrl+C`; the shutdown hook tells connected clients to disconnect
cleanly instead of letting them time out.

> **The game mode is a server setting.** There is no mode picker in the phone
> UI: whatever you put in `server.properties` or pass as `--mode` is what
> everyone plays. Clients send a `preferredMode` hint in the handshake and the
> server deliberately ignores it — otherwise the first phone to connect (which
> always asks for DM) would flip a TDM server to deathmatch and wipe the score.

---

## 7. Building from source

Requirements: **JDK 17+**. For the APK also the **Android SDK** with
`platforms;android-34` and `build-tools;34.0.0`.

```bash
# everything: tests, server.zip, both APKs  → release/
scripts/build-release.sh          # Linux / macOS
scripts\build-release.bat         # Windows
```

Or by hand:

```bash
./gradlew :shared:test :server:test          # 59 JVM tests
./gradlew :client-android:testDebugUnitTest  # 15 client-logic tests
./gradlew :server:packageServer              # → release/server.zip
./gradlew :client-android:assembleRelease    # → release/lanfps-client-release.apk
```

`:client-android` is **only included in the build when an Android SDK is
configured** (`ANDROID_SDK_ROOT`, `ANDROID_HOME`, or `local.properties`). On a
machine without the SDK the server still builds normally — the module simply
isn't in the graph.

The release APK is signed with the checked-in development key
(`keystore/lanfps.keystore`, all passwords `lanfps`). That is fine for a LAN
game; it is obviously not a key you would publish with. `build-release.sh`
regenerates it if it is missing.

### Test coverage

74 tests, all green:

| Suite | Tests | Covers |
|---|---|---|
| `shared.ProtocolTest` | 11 | header, CRC32, round-trips, truncation, quantisation |
| `shared.ArenaAndPhysicsTest` | 16 | arena hash, spawns, collision, determinism |
| `server.PhysicsTest` | 8 | walls, gravity, jump, crouch headroom, bounds |
| `server.RaycastTest` | 9 | line of sight, cover, range, teams, dead entities |
| `server.BotMatchTest` | 11 | nav graph, bot states, scoring, match end, post-match freeze |
| `server.ConnectHandshakeTest` | 4 | real UDP handshake against a real server: mode, team, arena hash |
| `client.ClientLogicTest` | 15 | interpolation, freeze, reordering, prediction, reconciliation |

The client tests are the important ones for feel: they assert that
`Prediction` reproduces the server simulation *exactly* over 30 ticks, that a
partially acknowledged snapshot replays the unacknowledged tail onto the same
spot, and that a 12 m disagreement snaps while a 0.2 m one is smoothed.

---

## 8. Firewall — the one thing that always breaks

Windows blocks inbound UDP by default. If the phone says
`No answer from 192.168.x.x:7777 after 16 tries`, this is why. In an
**Administrator** PowerShell:

```powershell
New-NetFirewallRule -DisplayName "LAN FPS Server UDP 7777" `
  -Direction Inbound -Protocol UDP -LocalPort 7777 `
  -Action Allow -Profile Private,Domain
```

or the `cmd` equivalent:

```bat
netsh advfirewall firewall add rule name="LAN FPS Server UDP 7777" ^
  dir=in action=allow protocol=UDP localport=7777
```

Also check that Windows calls your Wi-Fi a **Private** network, not Public:
*Settings → Network & Internet → Wi-Fi → your network → Private*.

Find the PC's address with `ipconfig` and read the **IPv4 Address** of the
adapter that is actually on the Wi-Fi (ignore VirtualBox / VMware / WSL / Hyper-V
adapters — picking one of those is the second most common failure).

---

## 9. Before you trust it: run the checklist

`docs/LAN_TEST_CHECKLIST.md` is a 12-step plan that goes from "server starts" to
"two phones shoot each other and the match ends correctly", including a
**loopback test with no phones at all**:

```bat
java -cp server.jar com.lanfps.server.tools.TestClientKt --ip=127.0.0.1 --nick=Probe --seconds=10
```

That prints snapshots/second, ping and malformed-packet counts. If it is green
but a phone still can't connect, the problem is the network or the firewall, not
the game — which is exactly the point of running it.

---

## 10. Known limitations

Deliberate scope choices, all in the name of "a simple working version beats a
big half-finished one":

* **One weapon.** A hitscan rifle: 25 damage, 8 rounds/s, infinite ammo (the HUD
  shows `∞`). No reload, no weapon switching, no grenades.
* **No sound.** Feedback is visual only — muzzle flash, tracers, damage flash,
  kill feed. Vibration on hit is wired up and permission-declared.
* **No lag compensation / rewind.** The server hit-tests against *present*
  positions, not against what the shooter saw. On a LAN (1–5 ms) this is
  imperceptible; over the internet it would need rewinding, which is out of
  scope for a LAN game.
* **No reliable-ordered channel.** Everything is unreliable UDP with sequence
  numbers plus redundancy; match events are re-sent for a few ticks rather than
  ACKed. Good enough for a switch, not for a lossy WAN link.
* **IPv4 only**, single subnet. Broadcast discovery does not cross routers —
  typing the IP always works.
* **No persistence.** Scores live for the length of a match; there are no
  accounts, no stats and nothing written to disk.
* **One map.** `arena01`. The format is data-driven, so adding another is a JSON
  file plus a line in `server.properties`, but only one ships.
* **Landscape only**, minSdk 24, OpenGL ES 3.0+ required.

---

## 11. Constraints this project was built under

* Kotlin/Java only. JOML permitted for maths (in the end the maths is hand-rolled
  in `MathTypes.kt` and JOML is not needed).
* Networking: `java.net.DatagramSocket` and nothing else — no Netty, no KryoNet,
  no third-party services, no matchmaking, no cloud.
* Android: framework SDK + OpenGL ES only. **No Jetpack Compose, no AndroidX
  dependencies at all** — the UI is `View`, `Canvas` and `GLSurfaceView`.
* Build: Gradle. No engine, no asset pipeline, no code generation.
* The finished game must run with the router's WAN cable unplugged.

---

## 12. Continuous integration

`.github/workflows/ci.yml` runs on every push:

* **Job `server`** — JDK 17, `:shared:test` + `:server:test`, builds
  `server.zip`, then boots the real jar headless for 15 s
  (`--selfTestSeconds=15`) and greps the log for `MATCH START` and a clean
  shutdown. A server that compiles but cannot actually run a match fails here.
* **Job `android`** — installs the Android SDK, generates a throwaway signing
  key (the real one is gitignored), runs the client unit tests, builds both
  APKs and verifies the release APK's signature with `apksigner`.

Both jobs upload their artifacts, so any green build gives you an installable
APK and a runnable server bundle without building anything locally.

---

## 13. Licence

**Apache License 2.0** — see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

Apache 2.0 gives you an explicit patent grant and a trademark carve-out on top of
permissive reuse. In exchange, if you redistribute this or a derivative you must:
keep the licence and existing attribution notices, ship a readable copy of
`NOTICE`, and state prominently which files you changed.

> The copyright line in `NOTICE` reads `2026 LAN FPS contributors`. Replace it
> with your own name or organisation before publishing.

**No third-party content is bundled.** The arena is data, the icon is a vector,
and there are no texture, model, audio or font assets at all — every surface is
shaded procedurally by GLSL written for this project. The only redistributed
third-party binary is the Gradle wrapper JAR (also Apache 2.0). Details in
[`NOTICE`](NOTICE).

---

## 14. Where to look when something is wrong

| Symptom | Read |
|---|---|
| Phone can't connect | `docs/TROUBLESHOOTING.md` §1 (IP, firewall, subnet, AP isolation) |
| Server window closes instantly | §2 (no Java, or a stack trace you can't see) |
| `Address already in use` | §3 |
| Other players invisible | §4 |
| Rubber-banding | §5 |
| High ping on a LAN | §6 |
| `! map mismatch with server !` | §7 |
| APK won't install | §8 |
| Black screen after ENTER MATCH | §9 |
| Gradle "Kotlin plugin loaded multiple times" | §12 (expected, harmless) |
