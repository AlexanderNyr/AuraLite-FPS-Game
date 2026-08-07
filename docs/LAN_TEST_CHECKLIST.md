# LAN test checklist — 1 Windows 10 PC + 2 Android phones

Print this, or keep it open on the PC. Every step has a concrete
**expected result**: if one fails, stop there and fix it before moving on —
almost every "it doesn't work" report is one of steps 1–6.

Total time: about 10 minutes the first time, under a minute afterwards.

---

## 0. Before you start

| Item | Requirement |
|---|---|
| PC | Windows 10 64-bit, Java 17+ installed (`java -version`) |
| Phones | Android 7.0 (API 24) or newer, OpenGL ES 3.0+ |
| Network | One Wi-Fi network. **Not** guest Wi-Fi, **not** mobile data |
| Files | `server.zip` on the PC, `lanfps-client-release.apk` on both phones |

> A phone on mobile data can never reach the PC. Turn mobile data **off** on
> both phones for the test; it removes an entire class of confusion.

---

## 1. Start the server on the PC

- [ ] Unzip `server.zip` to something short, e.g. `C:\lanfps\`
- [ ] Double-click `run-server.bat`

**Expected:** a console window that stays open and prints, in this order:

```
[INFO ] LAN FPS server 1.0.0
[INFO ] listening on 0.0.0.0:7777
[INFO ] protocolVersion=1
[INFO ] arena=arena01 60x40m, 24 brushes (23 solid), 8 spawns, 16 waypoints, hash=0x01895281
[INFO ] mode=DM  matchTime=300s  killLimit=20  maxPlayers=8
[INFO ] nav graph: 16 nodes, 60 links, 0 isolated
[INFO ] bots spawned: 4
```

❌ **If the window flashes and closes** → Java is missing. Install Temurin 17.
❌ **If you see `Address already in use`** → another server is running, or the
port is taken. Close it, or start with `run-server.bat --udpPort=7778`
(and type 7778 on the phones too).

---

## 2. Allow the server through the Windows firewall

- [ ] When Windows shows *"Windows Defender Firewall has blocked some features
      of Java"*, tick **Private networks** and click **Allow access**.

If you already dismissed that popup, open **Command Prompt as Administrator**:

```bat
netsh advfirewall firewall add rule name="LAN FPS UDP 7777" dir=in action=allow protocol=UDP localport=7777
```

- [ ] Check the Wi-Fi is a **Private** network:
      Settings → Network & Internet → Wi-Fi → *your network* → Network profile → **Private**

**Expected:** the rule appears in `wf.msc` (Inbound Rules) with a green tick.

---

## 3. Find the PC's IP address

- [ ] In Command Prompt: `ipconfig`
- [ ] Under the **Wi-Fi** adapter, read **IPv4 Address**, e.g. `192.168.1.25`

**Expected:** an address starting with `192.168.`, `10.` or `172.`.

❌ **If it starts with `169.254.`** → the PC is not actually on the Wi-Fi.
❌ **Ignore** any adapter named *vEthernet*, *VirtualBox*, *VMware*, *Hyper-V*.

Write it here: `_______________________`

---

## 4. Local loopback test (still no phones)

This proves the server works before the network is involved.

- [ ] Open a **second** Command Prompt in `C:\lanfps\`
- [ ] Run:

```bat
java -cp server.jar com.lanfps.server.tools.TestClientKt --ip=127.0.0.1 --nick=Tester --seconds=10
```

**Expected** (numbers will vary slightly):

```
connected: playerId=1 team=NONE mode=DM
...
RESULT: PASS
  snapshots       : 296  (29.6 /s)
  entities seen   : 5
  ping            : 1 ms
  input acked     : yes
  malformed       : 0
```

❌ **`RESULT: FAIL` or 0 snapshots** → the server itself has a problem; nothing
about the phones will help. Re-download `server.zip`.

---

## 5. Install the APK on both phones

- [ ] Copy `lanfps-client-release.apk` to each phone (USB, or a USB stick, or
      any file transfer — **no internet needed**)
- [ ] Tap it in the Files app
- [ ] Android will say *"For your security, your phone is not allowed to install
      unknown apps from this source"* → **Settings** → allow → **Install**

**Expected:** an app called **LAN FPS** with a crosshair icon.

---

## 6. Check the phones are on the same subnet

- [ ] Launch **LAN FPS** on phone 1
- [ ] Top right of the menu shows `this phone: 192.168.1.xx (wlan0)`

**Expected:** the first three numbers match the PC's IP.
PC `192.168.1.25` → phone must be `192.168.1.something`.

❌ **Different third number** (e.g. phone `192.168.43.x`) → the phone is on a
different network (often a hotspot). Reconnect it to the same Wi-Fi.

---

## 7. Connect phone 1

- [ ] Nickname: `P1`
- [ ] Server IP: the address from step 3
- [ ] Port: `7777`
- [ ] Tap **CONNECT**

**Expected within ~1 second:**
- toast *"Connected as P1"*
- the lobby appears listing `P1` plus the 4 bots
- the PC console prints `player connected: id=1 'P1' from 192.168.1.xx`

❌ **"No answer from … after 16 tries"** → firewall (step 2), wrong IP (step 3),
or router AP-isolation. See `TROUBLESHOOTING.md`.

- [ ] Tap **ENTER MATCH**

**Expected:** first-person view, crosshair, health `100`, ping in single or
low double digits, bots visibly running around and shooting.

---

## 8. Connect phone 2

Repeat step 7 with nickname `P2`.

**Expected:**
- PC console prints a second `player connected: id=2 'P2'`
- **phone 1 can see phone 2's character**, with the name `P2` floating above it
- **phone 2 can see phone 1**
- both scoreboards (hold **TAB**, top-right) list `P1`, `P2` and the 4 bots

---

## 9. Gameplay verification

Do these with both phones in the match:

- [ ] **Movement** — left thumb drags: the player walks. Release: stops
      immediately, no drifting.
- [ ] **Look** — right thumb drags: the view turns smoothly, no jitter.
- [ ] **Collision** — walk into a wall/crate: you stop, you do not pass through.
- [ ] **Jump** — JUMP button: you hop and land. You can jump onto a 1 m crate.
- [ ] **Crouch** — CROUCH button: you get shorter and slower; press again to stand.
- [ ] **Shooting** — hold FIRE: tracers, muzzle flash, recoil on the weapon model.
- [ ] **P1 shoots P2** — P2's health bar drops by 25 per hit; P1 sees a yellow
      hit marker on the crosshair.
- [ ] **Kill** — after 4 hits P2 dies: red "YOU DIED" screen with a countdown,
      P1's kill counter goes up, both phones see the kill in the feed.
- [ ] **Respawn** — after 3 seconds P2 is alive again at full health, at a spawn
      point, not inside a wall.
- [ ] **Bots** — bots chase, shoot, take cover, die and respawn. They do **not**
      shoot through walls.
- [ ] **Latency feel** — moving feels instant on your own phone (client
      prediction); other players move smoothly, never teleporting (interpolation).

---

## 10. Match end

- [ ] Wait for the timer to reach `0:00`, or for someone to hit the kill limit
      (default 20).

**Expected:** both phones show the results screen with the same final table,
in the same order. A new match starts automatically after ~12 seconds.

---

## 11. Robustness (the parts that usually break)

- [ ] **Background/foreground** — press Home on phone 1, wait 5 s, come back.
      Expected: it reconnects state and keeps playing, or shows a clear
      "lost connection" message with the menu — never a frozen black screen.
- [ ] **Walk out of Wi-Fi range** — phone 1 loses the network.
      Expected: after ~5 s, "Lost connection to the server", back to the menu.
      The PC console logs the timeout and frees the slot. Phone 2 keeps playing.
- [ ] **Rejoin** — phone 1 reconnects with the same nickname. Expected: it works
      and gets a fresh scoreboard entry.
- [ ] **Kill the server** (Ctrl+C) while both phones play.
      Expected: both phones return to the menu with a clear message; neither
      crashes.
- [ ] **Restart the server**, reconnect both phones. Expected: everything works.

---

## 12. Sign-off

| Check | P1 | P2 |
|---|---|---|
| Connects in under 2 s | ☐ | ☐ |
| Ping ≤ 30 ms | ☐ | ☐ |
| Sees the other player move smoothly | ☐ | ☐ |
| Can damage and kill the other player | ☐ | ☐ |
| Scoreboards agree | ☐ | ☐ |
| Survives a 5-minute match with no crash | ☐ | ☐ |

Server console at the end should show **no** `WARN`/`ERROR` lines other than
expected client timeouts.
