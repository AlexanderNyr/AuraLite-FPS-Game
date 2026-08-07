package com.lanfps.server

import kotlin.system.exitProcess

/**
 * Entry point for the headless Windows 10 server.
 *
 * Usage:
 *   java -jar server.jar
 *   java -jar server.jar --mode=TDM --botCount=6 --udpPort=7777
 *   java -jar server.jar --selftest        (run 15 s of simulation, then exit)
 */
fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        printUsage()
        return
    }

    val config = try {
        ServerConfig.load(args)
    } catch (e: Exception) {
        Log.error("failed to load configuration", e)
        exitProcess(2)
    }

    val server = GameServer(config)

    // Ctrl+C / taskkill -> close sockets and log disconnects cleanly.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            Log.info("received shutdown signal (Ctrl+C)")
            server.stop()
            // Give the loop a moment to finish its shutdown sequence.
            Thread.sleep(400)
        },
    )

    try {
        server.start()
    } catch (e: java.net.BindException) {
        Log.error(
            "UDP port ${config.udpPort} is already in use. " +
                "Close the other server instance, or start with --udpPort=7778",
        )
        exitProcess(3)
    } catch (e: Exception) {
        Log.error("server crashed", e)
        exitProcess(1)
    }
}

private fun printUsage() {
    println(
        """
        LAN FPS server

        Usage:
          java -jar server.jar [options]

        Options (also settable in server.properties next to the jar):
          --udpPort=7777           UDP port to listen on
          --bindAddress=0.0.0.0    interface to bind (0.0.0.0 = all)
          --mode=DM|TDM            ruleset
          --botCount=4             number of AI opponents (0-16)
          --maxPlayers=8           human player slots
          --matchTimeSeconds=300   match length
          --killLimit=20           score limit
          --botDifficulty=0.55     0.0 (easy) .. 1.0 (hard)
          --enableDiscovery=true   answer LAN broadcast discovery
          --logLevel=INFO          DEBUG | INFO | WARN | ERROR
          --selftest               simulate 15 s headless, then exit
          --help                   this text
        """.trimIndent(),
    )
}
