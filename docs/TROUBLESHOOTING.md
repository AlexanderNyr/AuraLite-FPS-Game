# Troubleshooting

Ordered by how often each thing actually happens. Work top to bottom.

---

## 1. The phone says "No answer from 192.168.x.x:7777 after 16 tries"

This is *the* common failure and it is always one of five things.

### 1.1 Wrong IP address
On the PC:

```bat
ipconfig
```

Use the **IPv4 Address** of the **Wi-Fi** adapter — not *vEthernet*, not
*VirtualBox Host-Only*, not *VMware*, not *Bluetooth*, not *Loopback*.

| Looks like | Meaning |
|---|---|
| `192.168.1.25` | ✅ normal home router |
| `10.0.0.14` | ✅ also fine |
| `172.20.10.3` | ✅ iPhone hotspot |
| `169.254.x.x` | ❌ the PC has **no** working network — fix the Wi-Fi first |
| `127.0.0.1` | ❌ loopback, only reachable by the PC itself |

### 1.2 Windows firewall
The server listens; the firewall silently drops the packets. Command Prompt
**as Administrator**:

```bat
netsh advfirewall firewall add rule name="LAN FPS UDP 7777" dir=in action=allow protocol=UDP localport=7777
```

Verify:

```bat
netsh advfirewall firewall show rule name="LAN FPS UDP 7777"
```

Remove later:

```bat
netsh advfirewall firewall delete rule name="LAN FPS UDP 7777"
```

Also confirm the Wi-Fi network profile is **Private**:
*Settings → Network & Internet → Wi-Fi → (network) → Network profile → Private*.
On a **Public** profile Windows blocks inbound traffic much more aggressively.

> Third-party security suites (Kaspersky, ESET, Norton, Bitdefender, Avast…)
> have their **own** firewall that ignores the Windows rule. If one is
> installed, add an allow rule there too, or pause it for the test.

### 1.3 Phone and PC on different networks
In the app menu, top right, the phone prints its own address:
`this phone: 192.168.1.31 (wlan0)`.

The first three numbers must match the PC.

| PC | Phone | Verdict |
|---|---|---|
| `192.168.1.25` | `192.168.1.31` | ✅ same subnet |
| `192.168.1.25` | `192.168.0.31` | ❌ different network |
| `192.168.1.25` | `10.x.x.x` | ❌ different network (often guest Wi-Fi) |

Turn **mobile data off** on the phone during testing — Android will happily
route traffic over the cellular network and the packets never reach the PC.

### 1.4 Router client isolation (AP isolation)
Many routers, and almost every guest network, block device-to-device traffic.
Symptoms: the phone can browse the internet, the PC can browse the internet,
but they cannot see each other.

- Router settings → Wireless → disable **AP Isolation** / **Client Isolation** /
  **Guest network isolation**.
- Or use a phone hotspot instead: enable the hotspot on phone 1, connect the PC
  **and** phone 2 to it, then run `ipconfig` on the PC for the new address.

### 1.5 Wrong port
If you started the server with `--udpPort=7778`, the phones must say `7778` too.

---

## 2. The server window opens and instantly closes

Java is not installed, or not on `PATH`. In Command Prompt:

```bat
java -version
```

Expected: `openjdk version "17..."` or newer. If you get *"'java' is not
recognized"*, install Temurin 17 from
<https://adoptium.net/temurin/releases/?version=17> (`.msi`, tick **Set JAVA_HOME
variable**), then open a **new** Command Prompt.

Run `run-server.bat` from an already-open Command Prompt to see the error text
before the window disappears:

```bat
cd C:\lanfps
run-server.bat
```

---

## 3. `Address already in use` / exit code 3

Another instance is still running, or something else owns UDP 7777.

```bat
netstat -a -n -o -p UDP | findstr 7777
taskkill /PID <the pid from the last column> /F
```

Or just use a different port on both sides:

```bat
run-server.bat --udpPort=7778
```

---

## 4. It connects, but the other player is invisible

- Check the scoreboard (**TAB**, top-right). If the other name is listed but you
  cannot see them, they are alive somewhere else on the map — the arena is
  60 × 40 m with sight-line breakers. Walk to the centre.
- If the name is **not** listed, that phone is connected to a *different* server
  (check the IP on both) or was dropped.
- The PC console logs every connect and disconnect with the source IP; that is
  the authoritative answer.

---

## 5. Movement rubber-bands / snaps backwards

The client predicts your movement locally and the server corrects it. Visible
correction means the two disagreed.

| Cause | Fix |
|---|---|
| Weak Wi-Fi | Move closer to the router; prefer the 5 GHz band |
| Microwave / crowded 2.4 GHz | Switch the router to 5 GHz or another channel |
| PC on Wi-Fi too | Plug the PC into the router with an Ethernet cable |
| Map mismatch | The HUD shows `! map mismatch with server !` — see §7 |

Turn on the debug overlay (pause button **II** → *Toggle net/debug overlay*).
`corrections` climbing fast, or `err` above ~0.1 m, means real packet loss.
On a healthy LAN `ping` is 1–15 ms and `snap/s` is ~30.

---

## 6. Ping is high (> 60 ms) on a LAN

- Wi-Fi power saving on the phone. Disable battery optimisation for LAN FPS:
  *Settings → Apps → LAN FPS → Battery → Unrestricted*.
- Another device is saturating the Wi-Fi (4K streaming, big download).
- 2.4 GHz band congestion — switch to 5 GHz.

---

## 7. HUD shows "! map mismatch with server !"

The `arena01.json` inside the APK has a different geometry hash from the one
next to `server.jar`. Prediction will fight the server near walls.

Fix: make sure both came from the same build. The server prints its hash at
startup (`hash=0x01895281`). Either
- delete `arena01.json` next to `server.jar` (the correct copy is bundled inside
  the jar and will be used automatically), or
- rebuild both from the same source tree:
  `./gradlew :server:packageServer :client-android:assembleRelease`

---

## 8. The APK will not install

- *"App not installed"* → an older LAN FPS with a **different signing key** is
  present. Uninstall it first.
- *"Blocked by Play Protect"* → tap **Install anyway** (the APK is self-signed;
  it is not on the Play Store).
- *"For your security…"* → grant *Install unknown apps* to the app you are
  installing **from** (Files, Chrome, Drive…), not to LAN FPS itself.
- Android older than 7.0 (API 24) is not supported.

---

## 9. Black screen after tapping ENTER MATCH

- Check logcat over USB: `adb logcat -s LANFPS`.
- The renderer logs its context at start-up:
  `GL_VERSION : OpenGL ES 3.2 v1.r26p0` — if that line is missing, the EGL
  context failed and the device does not have OpenGL ES 3.0.
- A shader compile failure is logged with the full info log and the source.

---

## 10. Sound / vibration

There is no audio in this build (by design — no assets ship with the game).
Button presses use the system haptic; if your phone has *Touch vibration*
disabled in system settings, buttons will be silent and still.

---

## 11. Building on a low-memory machine

The default `gradle.properties` targets a normal desktop (2 GB heap). On a
machine with ≤ 2 GB RAM, create/edit `~/.gradle/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx1400m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC
org.gradle.parallel=false
org.gradle.workers.max=1
org.gradle.daemon=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false
```

`GRADLE_USER_HOME/gradle.properties` overrides the project file. The key setting
is `kotlin.compiler.execution.strategy=in-process`: without it Gradle forks a
second JVM for the Kotlin compiler and the two together exhaust the machine.

Also make sure swap exists — a Linux box with **no** swap will lock up rather
than fail cleanly:

```bash
sudo fallocate -l 3G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
```

---

## 12. Gradle: "Kotlin Gradle plugin was loaded multiple times"

A warning, not an error — it is emitted because `:server` (Kotlin JVM) and
`:client-android` (Kotlin Android) each load the plugin in their own
classloader. That separation is deliberate: putting Kotlin on the *root*
buildscript classpath makes the Kotlin Android plugin fail with
`Could not generate a decorated class for KotlinAndroidTarget`, because it can
no longer see AGP. The build is correct; the warning can be ignored.

---

## 13. Useful commands

```bash
# server, verbose, team deathmatch, 6 bots
java -jar server.jar --mode=TDM --botCount=6 --logLevel=DEBUG

# headless self-test, no clients needed
java -jar server.jar --selftest

# protocol-level test client (also the best LAN diagnostic)
java -cp server.jar com.lanfps.server.tools.TestClientKt --ip=192.168.1.25 --nick=Probe --seconds=10

# what the phone is doing
adb logcat -s LANFPS

# install over USB
adb install -r release/lanfps-client-release.apk
```
