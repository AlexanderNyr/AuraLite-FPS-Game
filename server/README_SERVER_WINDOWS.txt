===============================================================================
 LAN FPS - dedicated server for Windows 10
===============================================================================

WHAT IS IN THIS ZIP
-------------------
  server.jar          the whole server (Kotlin runtime included - no installs)
  run-server.bat      double-click this to start it
  server.properties   settings: game mode, bot count, match length, port
  arena01.json        the map. Must match the one inside the Android APK.
  README_SERVER_WINDOWS.txt   this file


1. WHAT YOU NEED
----------------
  * Windows 10 (64-bit)
  * Java 17 or newer.  Check by opening Command Prompt and typing:

        java -version

    If you get "not recognised", install Temurin 17 (free):
        https://adoptium.net/temurin/releases/?version=17
    Choose the .msi installer and tick "Set JAVA_HOME variable".

  * The PC and both phones on the SAME Wi-Fi network.


2. START THE SERVER
-------------------
  1. Unzip this folder somewhere simple, e.g.  C:\lanfps\
  2. Double-click  run-server.bat
  3. A console window opens and prints something like:

        [INFO ] LAN FPS server 1.0.0
        [INFO ] listening on 0.0.0.0:7777
        [INFO ] protocolVersion=1
        [INFO ] arena=arena01 ...
        [INFO ] mode=DM  matchTime=300s  killLimit=20
        [INFO ] bots spawned: 4

     The batch file also prints the PC's IPv4 addresses. Write down the one
     that starts with 192.168.  or  10.  - that is what you type on the phones.

  Leave this window open while you play. Ctrl+C stops the server.


3. OPEN THE FIREWALL (usually needed once)
------------------------------------------
  Windows will normally show a "Windows Defender Firewall has blocked some
  features of Java" popup the first time. Tick "Private networks" and click
  "Allow access".

  If you missed the popup, open Command Prompt AS ADMINISTRATOR and run:

      netsh advfirewall firewall add rule name="LAN FPS UDP 7777" ^
            dir=in action=allow protocol=UDP localport=7777

  To remove it later:

      netsh advfirewall firewall delete rule name="LAN FPS UDP 7777"

  Also make sure the Wi-Fi connection is set to "Private", not "Public":
      Settings -> Network & Internet -> Wi-Fi -> (your network) -> Private


4. FIND THE PC'S IP ADDRESS
---------------------------
  Command Prompt:

      ipconfig

  Look under your Wi-Fi adapter for "IPv4 Address", e.g. 192.168.1.25
  Type exactly that on both phones, with port 7777.


5. CHANGING SETTINGS
--------------------
  Edit server.properties, then restart the server. The common ones:

      mode=DM              DM = free-for-all, TDM = team deathmatch
      botCount=4           0..8 AI opponents
      botDifficulty=0.55   0.0 easy .. 1.0 hard
      matchTimeSeconds=300 match length
      killLimit=20         score needed to win early
      udpPort=7777         change if 7777 is taken

  Anything in that file can also be passed on the command line, which
  overrides the file:

      run-server.bat --mode=TDM --botCount=6
      run-server.bat --udpPort=7778
      run-server.bat --help


6. QUICK SELF-TEST (no phones needed)
-------------------------------------
  Run a headless simulation for 15 seconds and exit:

      java -jar server.jar --selftest

  Or run the built-in test client against a running server, from a second
  Command Prompt window:

      java -cp server.jar com.lanfps.server.tools.TestClientKt --ip=127.0.0.1 --nick=Tester --seconds=10

  It prints how many snapshots arrived, the measured ping and whether any
  packet was malformed. If that works but the phones do not connect, the
  problem is the network or the firewall, not the server.


7. IF SOMETHING GOES WRONG
--------------------------
  "Address already in use" / exit code 3
        Another copy of the server is still running, or something else owns
        UDP 7777. Close the other console, or start with --udpPort=7778 and
        type 7778 on the phones too.

  Phones time out at "Connecting ... (try 16/16)"
        1. Are they on the same Wi-Fi as the PC? (not mobile data, not a
           guest network)
        2. Is the firewall rule in place? (section 3)
        3. Does the IP on the phone match ipconfig exactly?
        4. Some routers have "AP isolation" / "client isolation" which blocks
           phone-to-PC traffic. Turn it off in the router settings.

  Everything connects but movement rubber-bands
        Wi-Fi interference. Move closer to the router, or switch the router
        to the 5 GHz band.

  See docs/TROUBLESHOOTING.md in the source project for the full list.
