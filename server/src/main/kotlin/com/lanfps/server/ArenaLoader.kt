package com.lanfps.server

import com.lanfps.shared.ArenaDef
import java.io.File

/**
 * Resolves the arena the server will run.
 *
 * Order:
 *   1. `arena01.json` next to the jar (lets an admin tweak the map without a rebuild)
 *   2. `arena01.json` bundled inside the jar
 *   3. the built-in definition compiled into `shared`
 *
 * Step 3 means a missing or corrupt map file can never stop the server booting.
 * The resulting geometry hash is logged and sent to clients so a mismatched map
 * shows up as an explicit warning instead of mysterious desync.
 */
object ArenaLoader {

    fun load(fileName: String): ArenaDef {
        val external = File(fileName)
        if (external.isFile) {
            try {
                val def = ArenaDef.fromJson(external.readText())
                Log.info("arena loaded from ${external.absolutePath}")
                return def
            } catch (e: Exception) {
                Log.warn("failed to parse ${external.absolutePath} ($e) - trying bundled copy")
            }
        }

        try {
            val stream = ArenaLoader::class.java.classLoader.getResourceAsStream(fileName)
            if (stream != null) {
                val text = stream.bufferedReader().use { it.readText() }
                val def = ArenaDef.fromJson(text)
                Log.info("arena loaded from bundled resource '$fileName'")
                return def
            }
        } catch (e: Exception) {
            Log.warn("failed to parse bundled '$fileName' ($e) - using built-in arena")
        }

        Log.warn("no usable '$fileName' found - using the built-in arena definition")
        return ArenaDef.builtinArena01()
    }
}
