package com.lanfps.shared

/** Render/collision material ids. The client maps these to palette colours. */
object Material {
    const val FLOOR = 0
    const val WALL = 1
    const val CRATE = 2
    const val PILLAR = 3
    const val COVER = 4
    const val RAMP = 5

    fun name(m: Int): String = when (m) {
        FLOOR -> "floor"; WALL -> "wall"; CRATE -> "crate"
        PILLAR -> "pillar"; COVER -> "cover"; RAMP -> "ramp"
        else -> "unknown"
    }

    fun fromName(s: String): Int = when (s.lowercase()) {
        "floor" -> FLOOR; "wall" -> WALL; "crate" -> CRATE
        "pillar" -> PILLAR; "cover" -> COVER; "ramp" -> RAMP
        else -> WALL
    }
}

/** One axis-aligned block of level geometry. */
class Brush(
    @JvmField val box: Aabb,
    @JvmField val material: Int = Material.WALL,
    /** Non-solid brushes are drawn but ignored by collision and raycasts. */
    @JvmField val solid: Boolean = true,
)

/** A place a player can appear, with the direction they face. */
class SpawnPoint(
    @JvmField val position: Vec3,
    @JvmField val yaw: Float,
    @JvmField val team: Team,
)

/**
 * Immutable arena description shared by the server (simulation) and the client
 * (rendering + prediction).
 *
 * Both sides load the *same* `arena01.json`, so collision on the client's
 * predicted movement matches the server exactly. [hash] lets the client detect a
 * mismatched map at connect time instead of silently desyncing.
 */
class ArenaDef(
    @JvmField val name: String,
    @JvmField val minX: Float,
    @JvmField val maxX: Float,
    @JvmField val minZ: Float,
    @JvmField val maxZ: Float,
    @JvmField val wallHeight: Float,
    @JvmField val brushes: List<Brush>,
    @JvmField val spawns: List<SpawnPoint>,
    @JvmField val waypoints: List<Vec3>,
) {
    /** Only the solid brushes — the list physics and raycasts iterate. */
    @JvmField
    val collision: List<Aabb> = brushes.filter { it.solid }.map { it.box }

    val width: Float get() = maxX - minX
    val depth: Float get() = maxZ - minZ

    fun spawnsFor(team: Team): List<SpawnPoint> {
        if (team == Team.NONE) return spawns
        val filtered = spawns.filter { it.team == team }
        return if (filtered.isEmpty()) spawns else filtered
    }

    /** Stable geometry fingerprint used to detect client/server map mismatch. */
    fun hash(): Int {
        var h = -2128831035 // FNV-1a 32-bit offset basis
        fun mix(v: Int) {
            h = h xor v
            h *= 16777619
        }
        fun mixF(f: Float) = mix(Math.round(f * 100f))
        mix(name.hashCode())
        mixF(minX); mixF(maxX); mixF(minZ); mixF(maxZ); mixF(wallHeight)
        for (b in brushes) {
            mixF(b.box.minX); mixF(b.box.minY); mixF(b.box.minZ)
            mixF(b.box.maxX); mixF(b.box.maxY); mixF(b.box.maxZ)
            mix(b.material); mix(if (b.solid) 1 else 0)
        }
        for (s in spawns) {
            mixF(s.position.x); mixF(s.position.y); mixF(s.position.z)
            mixF(s.yaw); mix(s.team.wire)
        }
        return h
    }

    fun describe(): String =
        "arena '$name' ${width.toInt()}x${depth.toInt()}m, ${brushes.size} brushes " +
            "(${collision.size} solid), ${spawns.size} spawns, ${waypoints.size} waypoints, " +
            "hash=0x%08X".format(hash())

    /**
     * Serialises back to the same shape [fromJson] reads. `arena01.json` is
     * generated with this, so the shipped file and [builtinArena01] can never
     * silently disagree (a unit test asserts the two hashes match).
     */
    fun toJson(): String {
        fun f(v: Float): String {
            val r = Math.round(v * 1000f) / 1000.0
            return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
        }
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"name\": \"").append(name).append("\",\n")
        sb.append("  \"bounds\": { \"minX\": ").append(f(minX))
            .append(", \"maxX\": ").append(f(maxX))
            .append(", \"minZ\": ").append(f(minZ))
            .append(", \"maxZ\": ").append(f(maxZ))
            .append(", \"wallHeight\": ").append(f(wallHeight)).append(" },\n")

        sb.append("  \"brushes\": [\n")
        for ((i, b) in brushes.withIndex()) {
            sb.append("    { \"min\": [").append(f(b.box.minX)).append(", ")
                .append(f(b.box.minY)).append(", ").append(f(b.box.minZ)).append("]")
                .append(", \"max\": [").append(f(b.box.maxX)).append(", ")
                .append(f(b.box.maxY)).append(", ").append(f(b.box.maxZ)).append("]")
                .append(", \"material\": \"").append(Material.name(b.material)).append("\"")
            if (!b.solid) sb.append(", \"solid\": false")
            sb.append(" }")
            if (i < brushes.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        sb.append("  \"spawns\": [\n")
        for ((i, s) in spawns.withIndex()) {
            sb.append("    { \"pos\": [").append(f(s.position.x)).append(", ")
                .append(f(s.position.y)).append(", ").append(f(s.position.z)).append("]")
                .append(", \"yaw\": ").append(f(s.yaw))
                .append(", \"team\": \"").append(s.team.name).append("\" }")
            if (i < spawns.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        sb.append("  \"waypoints\": [\n")
        for ((i, w) in waypoints.withIndex()) {
            sb.append("    [").append(f(w.x)).append(", ").append(f(w.y))
                .append(", ").append(f(w.z)).append("]")
            if (i < waypoints.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    companion object {
        /**
         * Built-in fallback arena, identical to `arena01.json`.
         *
         * Original layout inspired by competitive-shooter *principles* (an open
         * centre with sight-line breakers, two flanking lanes, staggered cover and
         * protected spawns on opposite sides). It is not a copy of any existing
         * map and uses no third-party assets.
         *
         *   Z-
         *   +--------------------------------------------------+
         *   |  corner        NORTH LANE               corner    |
         *   |------------ divider ------ gap ------ divider ----|
         *   | RED            open centre / pillar          BLUE |
         *   | spawns                                     spawns |
         *   |------------ divider ------ gap ------ divider ----|
         *   |  corner        SOUTH LANE               corner    |
         *   +--------------------------------------------------+
         *   Z+
         */
        fun builtinArena01(): ArenaDef {
            val brushes = ArrayList<Brush>()

            fun box(
                x0: Float, y0: Float, z0: Float,
                x1: Float, y1: Float, z1: Float,
                mat: Int, solid: Boolean = true,
            ) = brushes.add(Brush(Aabb().set(x0, y0, z0, x1, y1, z1), mat, solid))

            val minX = -30f; val maxX = 30f
            val minZ = -20f; val maxZ = 20f
            val wallH = 4f

            // Floor: drawn only. The ground plane at y=0 is handled analytically
            // by the movement code, so making this solid would be redundant.
            box(minX, -0.5f, minZ, maxX, 0f, maxZ, Material.FLOOR, solid = false)

            // Perimeter walls (1 m thick, placed outside the playable rectangle).
            box(minX - 1f, 0f, minZ - 1f, maxX + 1f, wallH, minZ, Material.WALL)  // north
            box(minX - 1f, 0f, maxZ, maxX + 1f, wallH, maxZ + 1f, Material.WALL)  // south
            box(minX - 1f, 0f, minZ - 1f, minX, wallH, maxZ + 1f, Material.WALL)  // west
            box(maxX, 0f, minZ - 1f, maxX + 1f, wallH, maxZ + 1f, Material.WALL)  // east

            // Lane dividers: separate the centre from the two flanking lanes.
            // Centre gaps (|x| < 6) and end gaps (|x| > 22) keep rotations open.
            box(-22f, 0f, -8f, -6f, 3f, -7f, Material.WALL)
            box(6f, 0f, -8f, 22f, 3f, -7f, Material.WALL)
            box(-22f, 0f, 7f, -6f, 3f, 8f, Material.WALL)
            box(6f, 0f, 7f, 22f, 3f, 8f, Material.WALL)

            // Centre pillar: breaks the long spawn-to-spawn sight line.
            box(-2f, 0f, -2f, 2f, 2.5f, 2f, Material.PILLAR)

            // Four climbable crates around the centre (1 m: jumpable).
            box(-8f, 0f, -6f, -6f, 1f, -4f, Material.CRATE)
            box(6f, 0f, 4f, 8f, 1f, 6f, Material.CRATE)
            box(-8f, 0f, 4f, -6f, 1f, 6f, Material.CRATE)
            box(6f, 0f, -6f, 8f, 1f, -4f, Material.CRATE)

            // Cover inside the two lanes.
            box(-16f, 0f, -15f, -14f, 1.5f, -13f, Material.COVER)
            box(14f, 0f, -15f, 16f, 1.5f, -13f, Material.COVER)
            box(-16f, 0f, 13f, -14f, 1.5f, 15f, Material.COVER)
            box(14f, 0f, 13f, 16f, 1.5f, 15f, Material.COVER)

            // Spawn shields so players are not instantly visible on respawn.
            box(-26f, 0f, -3f, -24f, 2f, 3f, Material.COVER)
            box(24f, 0f, -3f, 26f, 2f, 3f, Material.COVER)

            // Corner blocks.
            box(-28f, 0f, -18f, -24f, 3f, -16f, Material.WALL)
            box(24f, 0f, 16f, 28f, 3f, 18f, Material.WALL)
            box(-28f, 0f, 16f, -24f, 3f, 18f, Material.WALL)
            box(24f, 0f, -18f, 28f, 3f, -16f, Material.WALL)

            // Spawns: RED on the west side, BLUE on the east side.
            // yaw 0 looks toward -Z, +yaw turns toward +X, so 90 faces east.
            val spawns = listOf(
                SpawnPoint(Vec3(-27f, 0f, -12f), 90f, Team.RED),
                SpawnPoint(Vec3(-27f, 0f, -5f), 90f, Team.RED),
                SpawnPoint(Vec3(-27f, 0f, 5f), 90f, Team.RED),
                SpawnPoint(Vec3(-27f, 0f, 12f), 90f, Team.RED),
                SpawnPoint(Vec3(27f, 0f, -12f), -90f, Team.BLUE),
                SpawnPoint(Vec3(27f, 0f, -5f), -90f, Team.BLUE),
                SpawnPoint(Vec3(27f, 0f, 5f), -90f, Team.BLUE),
                SpawnPoint(Vec3(27f, 0f, 12f), -90f, Team.BLUE),
            )

            // Bot patrol graph: north lane, centre, south lane + two connectors.
            // The lane rows sit at z = -/+11, i.e. in the clear corridor BETWEEN
            // the lane cover (z 13..15) and the divider (z 7..8). Putting them on
            // top of the cover would leave the nodes mutually invisible and the
            // navigation graph disconnected.
            val waypoints = listOf(
                Vec3(-24f, 0f, -11f), Vec3(-12f, 0f, -11f), Vec3(0f, 0f, -11f),
                Vec3(12f, 0f, -11f), Vec3(24f, 0f, -11f),
                Vec3(-20f, 0f, 0f), Vec3(-10f, 0f, 0f),
                Vec3(10f, 0f, 0f), Vec3(20f, 0f, 0f),
                Vec3(-24f, 0f, 11f), Vec3(-12f, 0f, 11f), Vec3(0f, 0f, 11f),
                Vec3(12f, 0f, 11f), Vec3(24f, 0f, 11f),
                Vec3(0f, 0f, -5f), Vec3(0f, 0f, 5f),
            )

            return ArenaDef(
                name = GameConstants.ARENA_NAME,
                minX = minX, maxX = maxX, minZ = minZ, maxZ = maxZ,
                wallHeight = wallH,
                brushes = brushes,
                spawns = spawns,
                waypoints = waypoints,
            )
        }

        /**
         * Builds an [ArenaDef] from parsed `arena01.json`.
         * Any structural problem throws, and callers fall back to [builtinArena01].
         */
        fun fromJson(text: String): ArenaDef {
            val root = MiniJson.asObject(MiniJson.parse(text))
            val name = root["name"] as? String ?: GameConstants.ARENA_NAME
            val bounds = MiniJson.asObject(root["bounds"])
            val minX = MiniJson.float(bounds["minX"])
            val maxX = MiniJson.float(bounds["maxX"])
            val minZ = MiniJson.float(bounds["minZ"])
            val maxZ = MiniJson.float(bounds["maxZ"])
            val wallHeight = bounds["wallHeight"]?.let { MiniJson.float(it) } ?: 4f

            val brushes = MiniJson.asArray(root["brushes"]).map { raw ->
                val o = MiniJson.asObject(raw)
                val min = MiniJson.asArray(o["min"])
                val max = MiniJson.asArray(o["max"])
                val aabb = Aabb().set(
                    MiniJson.float(min[0]), MiniJson.float(min[1]), MiniJson.float(min[2]),
                    MiniJson.float(max[0]), MiniJson.float(max[1]), MiniJson.float(max[2]),
                )
                Brush(
                    box = aabb,
                    material = Material.fromName(o["material"] as? String ?: "wall"),
                    solid = o["solid"] as? Boolean ?: true,
                )
            }

            val spawns = MiniJson.asArray(root["spawns"]).map { raw ->
                val o = MiniJson.asObject(raw)
                val p = MiniJson.asArray(o["pos"])
                SpawnPoint(
                    position = Vec3(
                        MiniJson.float(p[0]), MiniJson.float(p[1]), MiniJson.float(p[2]),
                    ),
                    yaw = o["yaw"]?.let { MiniJson.float(it) } ?: 0f,
                    team = when ((o["team"] as? String)?.uppercase()) {
                        "RED" -> Team.RED
                        "BLUE" -> Team.BLUE
                        else -> Team.NONE
                    },
                )
            }

            val waypoints = (root["waypoints"] as? List<*>)?.map { raw ->
                val a = MiniJson.asArray(raw)
                Vec3(MiniJson.float(a[0]), MiniJson.float(a[1]), MiniJson.float(a[2]))
            } ?: emptyList()

            require(brushes.isNotEmpty()) { "arena has no brushes" }
            require(spawns.isNotEmpty()) { "arena has no spawn points" }

            return ArenaDef(
                name, minX, maxX, minZ, maxZ, wallHeight,
                brushes, spawns, waypoints,
            )
        }
    }
}
