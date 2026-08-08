# LAN FPS — improvement plan

> ## STATUS (2026-08): everything in this document is implemented ✅
>
> The plan below is kept verbatim as the design rationale. This is where each
> item landed (protocol is now **v3**, 124 tests, all green):
>
> | Item | Status | Implementation |
> |---|---|---|
> | P0-1 nicknames → MTU | ✅ done | names out of snapshots into `LOBBY_STATE` roster; `SNAPSHOT_MAX_BYTES=1400` enforced |
> | P0-2 reconnect | ✅ done | `resumeToken`, zombie sessions, `Phase.RECONNECTING` (`ConnectHandshakeTest`) |
> | P0-3 CONNECT flood | ✅ done | global + per-IP rate limits, sessions-per-IP cap, plus optional `password=` (`SessionManager`, `MatchFlowTest`) |
> | P0-4 MTU test | ✅ done | packet-budget assertions at 1400 B |
> | P1-1 lag compensation | ✅ done | `PositionHistory` rewind ≈90 ms + RTT/2, capped 250 ms (`LagCompensationTest`) |
> | P1-2 delta compression | ✅ done | FULL keyframes + per-recipient DELTAs (`SnapshotDelta`, `SnapshotDeltaTest`) |
> | P1-3 sound | ✅ done | procedural `SoundManager` (gunshot/hit/damage/death/respawn/jingle); vibration buzz on damage |
> | P1-4 reliable events | ✅ done | `eventSeq` + header ack re-send loop |
> | P2-1 weapons | ✅ done | `WeaponDef`/`Weapons` catalogue: RIFLE, SHOTGUN (7-pellet spread), SNIPER; WPN button; per-weapon HUD/viewmodel/tracers (`WeaponsTest`) |
> | P2-2 ammo/reload | ✅ done | `infiniteAmmo=` config key (default false); magazines, RLD button, auto-reload of a dry gun |
> | P2-3 maps | ✅ done | `arena02` *Crossfire* + `arena03` *Foundry*; `mapRotation=`; server announces arena in `MATCH_START`, client hot-loads + hash-verifies the asset (`MapsTest`) |
> | P2-4 AI | ✅ done | hearing within 30 m → SEEK, EVADE runs to real cover, per-bot skill spread around `botDifficulty`; bots pick weapons by range |
> | P2-5 spectator | ✅ done | dead players watch their killer until respawn |
> | P2-6 mode in UI | ✅ done | mode + kill goal in the lobby and on the HUD top bar (`LOBBY_STATE.killLimit`) |
> | P2-7 team balance | ✅ done | auto re-deal by score between matches (`World.balanceTeams`) |
> | P3-1 lossy tests | ✅ done | `tools/ChaosProxy.kt` + `ChaosNetworkTest` (0/10/20 % loss, 60–120 ms latency, jitter, reorder) |
> | P3-2 CI | ✅ done | `.github/workflows/ci.yml` (+ release publishing) |
> | P3-3 metrics | ✅ done | tick p50/p95/p99 + real tps in the stats line; per-session rttP95 + uplink loss; `--statsCsv=` |
> | P3-4 smaller things | ✅ done | `MODE_VOTE` lobby vote (strict majority, one match); R8 on with `proguard-rules.pro`; `NetworkClientTest` (8 wire-level tests); `SessionManager` split out of `GameServer`; `values-ru/`; README plainly says "dev key — do not publish" |
>
> ---
>
> # Original plan (verbatim)

Written from a code audit, not from general advice: everything below was checked
against the current tree (10,318 lines of Kotlin, 74 tests, zero TODO/FIXME
markers in the source).

**What is already good and should not be touched:** the authoritative server, a
single `MovementSolver` shared by both sides, prediction with reconciliation, the
90 ms interpolation buffer that freezes instead of extrapolating, CRC32 on every
packet, the input token bucket, deterministic physics, 74 green tests, and a
clean APK + `server.zip` build. The foundation is right. What follows is what the
foundation does not cover.

---

## Priority summary

| # | Problem | Risk | Effort | Payoff |
|---|---|---|---|---|
| **P0-1** | Snapshots exceed the MTU on a full server → IP fragmentation | **High** | S | Huge |
| **P0-2** | No reconnect: 8 s of silence drops you to the menu and loses your score | High | M | Large |
| **P0-3** | No CONNECT flood protection — one script can take the server down | Medium | S | Medium |
| **P0-4** | The packet-budget test asserts 2048 B, above the MTU, so it misses P0-1 | High | XS | Large |
| **P1-1** | No lag compensation: "I shot him dead centre and missed" | Medium | L | Large |
| **P1-2** | No delta compression: full state 30×/s | Low | M | Medium |
| **P1-3** | No sound at all — for a shooter that is half the experience | — | M | Large |
| **P1-4** | `MATCH_EVENT` is unreliable: the kill feed can be lost | Low | M | Medium |
| **P2-*** | Content: one weapon, one map, simple AI, no spectator | — | L | Large |
| **P3-*** | Engineering: no CI, no packet-loss tests, no metrics | Medium | M | Medium |

`XS` < 1 h · `S` ≈ half a day · `M` ≈ 1–2 days · `L` ≈ a week

---

# P0. Critical — will break on a real LAN

## P0-1. Snapshots overflow the MTU → IP fragmentation

**This is the main finding of the audit.**

`GameConstants.MAX_PACKET_SIZE = 10 * 1024`, but the real limit for an
unfragmented UDP datagram on Ethernet/Wi-Fi is **1472 bytes**
(1500 MTU − 20 IP − 8 UDP).

The nickname is sent in **every** snapshot for **every** entity:

```
EntityState.WIRE_SIZE_FIXED = 36 B
+ nickname: writeString(name, MAX_NICKNAME_LENGTH * 4)  → up to 65 B
```

| Server population | ASCII names | **Non-Latin names (2 B/char)** |
|---|---|---|
| 4 bots + 2 players | ~310 B | ~450 B |
| 16 bots + 4 players | ~970 B | **~1,415 B** ⚠️ |
| 16 bots + 8 players (`maxPlayers`) | ~1,160 B | **~1,690 B ❌ over MTU** |

The threshold is roughly **21 entities with two-byte nicknames**. In other words
the stock configuration `maxPlayers=8, botCount=16` already fragments as soon as
players use Cyrillic, Greek, Arabic, CJK or accented names.

Why this matters here specifically: IP reassembles a fragmented datagram from all
of its fragments, so **losing one fragment destroys the whole snapshot**. On
Wi-Fi with 1–2 % loss that turns into 5–10 % of snapshots lost — visible
stuttering for every remote player. And it only shows up on a full server, which
is exactly when you are demoing it.

**Fix, in increasing order of effort:**

1. **Remove nicknames from snapshots** — the big win, `S`.
   Send names once in `LOBBY_STATE` / on connect and whenever the roster changes;
   keep only `id` in the snapshot. An entity drops from 69–101 B to **36 B**,
   24 entities → ~900 B, a 2× safety margin.
   The comment in `EntityState.kt` ("At 16 entities that costs well under 1 KB")
   is true for ASCII and 16 entities — it needs rewording, the assumption does
   not hold.
2. **Lower `MAX_PACKET_SIZE` to 1200** and assert on it when building a snapshot,
   so the problem fails a test instead of failing over Wi-Fi.
3. If more than ~32 entities is ever needed, page the snapshot
   (`entityPage`/`pageCount` in the snapshot header).

---

## P0-2. No reconnect

`NetworkClient` detects server silence, but there is no recovery: after the 8 s
timeout the client returns to the menu, the server drops the session, and the
player's score is gone. On Wi-Fi this happens from a single deep-sleep or an
access-point handover.

**What to do:**
- Server: hand out a `resumeToken` (u32) in `CONNECT_ACCEPTED` and keep the
  session in a `ZOMBIE` state for ~30 s after it goes quiet, preserving `id`,
  score and team.
- Client: on silence > 2 s do not bail to the menu — show a
  "Connection lost — reconnecting…" overlay and resend `CONNECT_REQUEST` with the
  token.
- Server: on a valid token, hand back the same entity instead of a new one.
- Call `Prediction.reset()` and re-`teleportTo` from the first snapshot after
  resuming.

**Verification:** a test that kills the client socket for 5 s against a live
server and asserts `playerId`, score and team survived.

---

## P0-3. No connection rate limit

The token bucket (`MAX_INPUTS_PER_SECOND=90`) only protects an already
established session. `handleConnect` is unbounded: a script sending
`CONNECT_REQUEST` from different source ports fills all 8 slots in a second and
burns CPU creating sessions and snapshots.

**What to do (`S`):**
- Cap new sessions at ~5/s globally and ~2/s per IP.
- Cap sessions per IP at 2 (two phones behind one NAT is rare — make it a
  `server.properties` key).
- Add a `rejectedConnects` counter to the `stats:` line.
- Optionally add `password=` to `server.properties` plus a field in
  `CONNECT_REQUEST` (a plain equality check, not crypto — this is a LAN).

---

## P0-4. The packet-budget test checks the wrong threshold

```kotlin
assertTrue(len < 2048, "snapshot unexpectedly large: $len bytes")
```

2048 is above the MTU, so this test would **pass** while P0-1 is broken. It also
uses 16 bots with short ASCII names — the most favourable case possible.

**What to do (`XS`, do this first):**
- Change the threshold to `< 1400`.
- Add a worst-case test: `maxPlayers` players + `botCount` bots, every one with a
  16-character two-byte nickname. It will **fail** today — which is the point;
  it pins the bug down before the fix lands.

---

# P1. Big effect on how the game feels

## P1-1. Lag compensation (rewind)

`Raycast` currently fires at **present** positions, while the player sees
opponents 90 ms in the past (interpolation buffer) plus half the RTT. On wired
LAN (1–5 ms) that is invisible, but on Wi-Fi with 20–50 ms of jitter it already
produces "shot him point-blank, missed". For a target crossing your view at
5.4 m/s with 120 ms of desync, the target is **65 cm** away from where you see
it — more than its 0.35 m radius. The miss is guaranteed.

**What to do (`L`):**
- Keep a ring buffer of every entity's position on the server, ~500 ms
  (30 snapshots).
- `InputCommand` already carries `clientTimeMs`; combine it with smoothed RTT to
  reconstruct the instant the shooter was looking at.
- On a shot, roll targets back to that instant, cast the ray, roll forward.
- Cap the rewind at 250 ms so lagging players gain no advantage.
- Add `lagCompensation=true` to `server.properties` so it can be toggled and
  compared.

**Verification:** a test with a target moving across the view and a shooter 120 ms
behind — a miss without compensation, a hit with it.

---

## P1-2. Delta-compressed snapshots

After P0-1 the traffic drops threefold anyway, so this is no longer urgent, but:
a full state currently goes out every 33 ms even though only a few active
entities actually changed between ticks.

**What to do (`M`):** a changed-field bitmask relative to the last snapshot the
client acknowledged (the header already has an `ack` field). Expect 60–70 %
savings. Keep sending a full snapshot once a second as a keyframe, otherwise a
client can never recover from a loss.

---

## P1-3. There is no sound at all

For a shooter this is the most noticeable gap: no gunshot, no hit, no footsteps,
no death. And the "no third-party assets" constraint is easy to honour, because
**the audio can be synthesised procedurally**: a gunshot is a noise burst with an
exponential decay envelope, a hit is a short click — all generated into a
`ShortArray` and played through `AudioTrack`/`SoundPool`. Not a single external
file.

**What to do (`M`):**
- `SoundSynth.kt`: generate PCM for the gunshot, taking damage, hitmarker,
  death, respawn, and the end-of-match countdown tick.
- Pan and attenuate by distance and azimuth relative to the camera — the data is
  already in the snapshot (the `firing` flag plus positions).
- Vibrate on damage: the `VIBRATE` permission is already declared in the
  manifest but never used.

---

## P1-4. Match events are not guaranteed

`MATCH_EVENT` (kills, match start/end) goes over unreliable UDP with a few ticks
of repetition. A loss means a missing kill-feed line or, worse, a missed
`MATCH_END` — the client stays stuck in combat until a snapshot arrives carrying
the new state.

**What to do (`M`):** a tiny reliable channel — number every event, have the
client acknowledge the highest one it has seen inside `CLIENT_INPUT` (the header
already reserves an `ack` field), and have the server repeat unacknowledged
events ~10×/s. The client drops duplicates by number.

---

# P2. Content and gameplay

| Item | Effort | Notes |
|---|---|---|
| **2–3 weapons** (shotgun, sniper) | M | The plumbing exists: `InputCommand` already has a `weapon` field that is simply unused. Needs `WeaponDef` in `shared`, spread, recoil, per-weapon `fireInterval`/damage. |
| **Ammo and reloading** | S | Turn `WEAPON_INFINITE_AMMO=true` into a config key. Changes the pacing a lot, for the better. |
| **A second and third map** | M | The format is already data-driven (`arena01.json`, 24 brushes). All that is missing is an editor/script plus map selection in `server.properties` and rotation between matches. |
| **Better AI** | M | Bots only see within a cone today. Add: hearing gunshots (~30 m radius → switch to `SEEK` toward the source), using cover, and a spread of skill levels (one global `botDifficulty` makes every bot identical — mixing them makes a match feel alive). |
| **Spectator / free camera** | S | Show the killer's view after you die instead of a black screen — cheap, and a big improvement in feel. |
| **Show the mode in the UI** | S | The mode is server-side only (correct), but the lobby should still *display* DM/TDM and the limits so players know the rules. |
| **Team balancing** | S | Teams can end up 3v1 when someone leaves. Auto-balance between matches. |

---

# P3. Engineering and quality

## P3-1. Testing against a lossy network — the biggest gap in the test suite

There are 74 tests, but **every network test runs over a perfect loopback**. All
the hard logic — reconciliation, interpolation, de-duplication, reconnect —
exists precisely because networks are bad, and that is exactly what is never
exercised.

**What to do (`M`, high payoff):** a UDP shaping proxy for tests — a `ChaosProxy`
between client and server with configurable loss (0–20 %), latency (0–200 ms),
jitter and reordering. Run `TestClient` through it and assert the client does not
crash, snapshots keep arriving, reconciliation converges and the score is
correct. This will find more bugs than everything else on this list.

## P3-2. No CI

There was no `.github/workflows`; everything was built by hand. A workflow is
included in this repo now (`ci.yml`): JDK 17 → `./gradlew test` → build
`server.zip` → build the APK when an Android SDK is available → upload
artifacts, with a Gradle cache, on every push.

## P3-3. Server observability

There is a `stats:` line but no percentiles. When someone says "it lags" there is
nothing to look at.

**What to do (`S`):** p50/p95/p99 tick duration, actual tick rate, and per
session: p95 RTT, loss (from gaps in `ack`), reconciliation count. Plus
`--statsCsv=stats.csv` for graphing after a test.

## P3-4. Smaller things that are immediately visible

- **`preferredMode` is now a dead field** — the client sends `DM`, the server
  ignores it. Either stop sending it or (better) turn it into a real lobby vote.
  As it stands it is a trap for the next developer.
- **The release APK is signed with a dev key** committed to the repo. Fine for a
  LAN, but the README should say plainly: do not publish with this key.
- **R8 is disabled** — deliberate, but `minifyEnabled=true` plus keep rules would
  cut ~30 % of the size for release. Low priority.
- **`NetworkClient` has no direct tests** — 837 lines, the largest file in the
  project, covered only indirectly through `TestClient`. Worth covering directly
  once `ChaosProxy` (P3-1) exists.
- **`GameServer.kt` is 535 lines** and does everything: socket, sessions, tick,
  snapshots, discovery, stats. It wants splitting into `SessionManager` and
  `NetworkDispatcher`. Not urgent, but it is growing.
- **Localisation**: the UI is English-only. `strings.xml` already exists, so
  adding `values-ru/` and others is cheap.

---

# Roadmap

### Sprint 1 — "will not fall over during the demo" (2–3 days)
1. P0-4: fix the test threshold and add the worst-case test *(it will fail — good)*
2. P0-1: remove nicknames from snapshots → the test goes green
3. P0-3: connection rate limit
4. P3-2: CI

**Done when:** a full server (8 players + 16 bots, two-byte nicknames) keeps
snapshots under 1400 B, a flood script cannot take the server down, CI is green.

### Sprint 2 — "a real network" (3–5 days)
5. P3-1: `ChaosProxy` plus tests at 5 % / 10 % / 20 % loss
6. P0-2: reconnect with `resumeToken`
7. P1-4: reliable match events

**Done when:** the game stays playable at 10 % loss and 100 ms jitter, and a 5 s
disconnect recovers without losing the score.

### Sprint 3 — "feels like a shooter" (3–5 days)
8. P1-3: procedural audio plus vibration
9. P1-1: lag compensation
10. P2: spectator after death, mode shown in the lobby

**Done when:** a hit on a strafing target at 100 ms RTT registers, and you can
hear the shot and the hitmarker.

### Sprint 4 — content
11. Second and third weapon plus ammo
12. Second map plus rotation
13. Better AI (hearing, cover, mixed difficulty)

---

## If you only do three things

1. **P0-1 + P0-4** — get nicknames out of snapshots. Half a day, and it removes
   the one bug guaranteed to show up on a full server with non-Latin names.
2. **P3-1** — `ChaosProxy`. All of the netcode logic was written for bad networks
   and has never once been tested on one.
3. **P1-3** — sound. The cheapest way to raise perceived quality, and it can be
   synthesised without a single third-party asset.
