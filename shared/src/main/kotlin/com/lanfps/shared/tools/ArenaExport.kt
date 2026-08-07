package com.lanfps.shared.tools

import com.lanfps.shared.ArenaDef
import java.io.File

/**
 * Dev tool: writes the built-in arena out as `arena01.json`.
 *
 * The shipped JSON is generated with this so the data file and
 * [ArenaDef.builtinArena01] always describe the same geometry (a unit test
 * asserts the hashes match).
 *
 * Usage: `java -cp <shared+stdlib> com.lanfps.shared.tools.ArenaExportKt out1.json [out2.json ...]`
 */
fun main(args: Array<String>) {
    val arena = ArenaDef.builtinArena01()
    val json = arena.toJson()

    if (args.isEmpty()) {
        print(json)
        return
    }
    for (path in args) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(json)
        println("wrote ${f.absolutePath} (${json.length} bytes)")
    }
    println(arena.describe())
}
