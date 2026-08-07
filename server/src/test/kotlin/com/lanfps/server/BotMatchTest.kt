package com.lanfps.server

import com.lanfps.shared.ArenaDef
import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.MatchState
import com.lanfps.shared.Team
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end headless simulation: run real matches full of bots and assert the
 * game actually plays itself — bots move, find each other, shoot, die, respawn
 * and score, without ever leaving the arena or shooting teammates.
 */
class BotMatchTest {

    private fun makeWorld(mode: GameMode, bots: Int, difficulty: Float = 0.8f): Pair<World, MatchController> {
        val arenaDef = ArenaDef.builtinArena01()
        val arena = ServerArena(arenaDef)
        val config = ServerConfig().apply {
            this.mode = mode
            botCount = bots
            botDifficulty = difficulty
            matchTimeSeconds = 60
            killLimit = 1000 // don't end early; we want the full window
        }
        val world = World(arena, config)
        world.setBotCount(bots)
        val match = MatchController(world, config)
        return world to match
    }

    private fun simulate(world: World, match: MatchController, seconds: Int) {
        val ticks = seconds * GameConstants.TICK_RATE
        repeat(ticks) {
            world.tick(GameConstants.TICK_DT)
            match.update(GameConstants.TICK_DT)
        }
    }

    /**
     * Steps the world exactly the way [GameServer.runLoop] does, including the
     * combat gate, and stops as soon as [until] is satisfied.
     *
     * @return true if the predicate fired before the budget ran out.
     */
    private fun simulateUntil(
        world: World,
        match: MatchController,
        maxSeconds: Int,
        until: () -> Boolean,
    ): Boolean {
        repeat(maxSeconds * GameConstants.TICK_RATE) {
            world.combatEnabled = match.isActive
            world.tick(GameConstants.TICK_DT)
            match.update(GameConstants.TICK_DT)
            if (until()) return true
        }
        return false
    }

    @Test
    fun `bots spawn into the world`() {
        val (world, _) = makeWorld(GameMode.DM, 4)
        assertEquals(4, world.bots.size)
        assertTrue(world.bots.all { it.alive }, "all bots should start alive")
        assertTrue(
            world.bots.all { world.physics.fits(it.body.position) },
            "a bot spawned inside geometry",
        )
    }

    @Test
    fun `bots patrol and actually move`() {
        val (world, match) = makeWorld(GameMode.DM, 4)
        val startPositions = world.bots.map { it.body.position.copy() }
        simulate(world, match, 5)

        var moved = 0
        for ((i, bot) in world.bots.withIndex()) {
            if (bot.body.position.distanceTo(startPositions[i]) > 2f) moved++
        }
        assertTrue(moved >= 3, "only $moved/4 bots moved meaningfully in 5 s")
    }

    @Test
    fun `bots stay inside the arena for a whole match`() {
        val (world, match) = makeWorld(GameMode.DM, 6)
        simulate(world, match, 45)

        val def = world.serverArena.def
        for (bot in world.bots) {
            val p = bot.body.position
            assertTrue(p.x.isFinite() && p.y.isFinite() && p.z.isFinite(), "${bot.name} has NaN position")
            assertTrue(
                p.x >= def.minX - 0.5f && p.x <= def.maxX + 0.5f,
                "${bot.name} left the arena on X: $p",
            )
            assertTrue(
                p.z >= def.minZ - 0.5f && p.z <= def.maxZ + 0.5f,
                "${bot.name} left the arena on Z: $p",
            )
            assertTrue(p.y >= -0.5f && p.y < 10f, "${bot.name} is at a bad height: $p")
        }
    }

    @Test
    fun `bots find and kill each other in deathmatch`() {
        val (world, match) = makeWorld(GameMode.DM, 6, difficulty = 0.9f)
        simulate(world, match, 60)

        val totalKills = world.entities.values.sumOf { it.kills }
        val totalDeaths = world.entities.values.sumOf { it.deaths }
        assertTrue(totalKills > 0, "bots never killed anyone in 60 s of DM")
        assertTrue(totalDeaths > 0, "nobody ever died")
        assertEquals(totalKills, totalDeaths, "every kill must produce exactly one death")
    }

    @Test
    fun `dead bots respawn`() {
        val (world, match) = makeWorld(GameMode.DM, 6, difficulty = 0.9f)
        simulate(world, match, 60)

        // After a long match with deaths, everyone should be alive again or
        // counting down — never permanently dead.
        assertTrue(world.entities.values.sumOf { it.deaths } > 0, "no deaths happened")
        for (e in world.entities.values) {
            if (!e.alive) {
                assertTrue(
                    e.respawnTimer > 0f && e.respawnTimer <= GameConstants.RESPAWN_DELAY_SEC,
                    "${e.name} is dead with a bad respawn timer ${e.respawnTimer}",
                )
            } else {
                assertTrue(e.health > 0, "${e.name} is alive with ${e.health} hp")
            }
        }
    }

    @Test
    fun `team deathmatch bots never kill their own team`() {
        val (world, match) = makeWorld(GameMode.TDM, 8, difficulty = 0.9f)
        // Teams must be balanced.
        val red = world.bots.count { it.team == Team.RED }
        val blue = world.bots.count { it.team == Team.BLUE }
        assertTrue(kotlin.math.abs(red - blue) <= 1, "teams unbalanced: RED=$red BLUE=$blue")

        simulate(world, match, 60)

        val teamKillTotal = world.score.redScore + world.score.blueScore
        val entityKillTotal = world.entities.values.sumOf { it.kills }
        assertTrue(entityKillTotal > 0, "no kills happened in TDM")
        assertEquals(
            entityKillTotal, teamKillTotal,
            "every TDM kill must be credited to exactly one team (no friendly fire)",
        )
    }

    @Test
    fun `match ends on the kill limit and restarts`() {
        val arenaDef = ArenaDef.builtinArena01()
        val arena = ServerArena(arenaDef)
        val config = ServerConfig().apply {
            mode = GameMode.DM
            botCount = 6
            botDifficulty = 1.0f
            matchTimeSeconds = 600
            killLimit = 3
        }
        val world = World(arena, config)
        world.setBotCount(6)
        val match = MatchController(world, config)

        var endedSeen = false
        repeat(120 * GameConstants.TICK_RATE) {
            world.tick(GameConstants.TICK_DT)
            match.update(GameConstants.TICK_DT)
            if (match.state == MatchState.ENDED) endedSeen = true
        }
        assertTrue(endedSeen, "match never reached the kill limit of 3 in 120 s")
    }

    @Test
    fun `match ends when the clock runs out`() {
        val arenaDef = ArenaDef.builtinArena01()
        val arena = ServerArena(arenaDef)
        val config = ServerConfig().apply {
            mode = GameMode.DM
            botCount = 2
            matchTimeSeconds = 5
            killLimit = 1000
        }
        val world = World(arena, config)
        world.setBotCount(2)
        val match = MatchController(world, config)

        // WARMUP -> ACTIVE on the first update.
        match.update(GameConstants.TICK_DT)
        assertEquals(MatchState.ACTIVE, match.state)

        repeat(6 * GameConstants.TICK_RATE) {
            world.tick(GameConstants.TICK_DT)
            match.update(GameConstants.TICK_DT)
        }
        assertEquals(MatchState.ENDED, match.state, "match should have ended on time")
    }

    @Test
    fun `snapshot stays far below the packet budget`() {
        val (world, match) = makeWorld(GameMode.TDM, 16)
        simulate(world, match, 3)

        val builder = SnapshotBuilder()
        val len = builder.build(world, match, 100, 1000L, 1)
        assertTrue(
            len < GameConstants.MAX_PACKET_SIZE,
            "snapshot with 16 entities is $len bytes, over budget",
        )
        // Sanity: should be well under 2 KB for 16 entities.
        assertTrue(len < 2048, "snapshot unexpectedly large: $len bytes")
        println("snapshot with ${world.entities.size} entities = $len bytes")
    }

    @Test
    fun `the score is frozen while the end-of-match result is on screen`() {
        val (world, match) = makeWorld(GameMode.TDM, 4)
        // A short match so the bots reach the limit quickly.
        world.config.killLimit = 3

        val ended = simulateUntil(world, match, 180) { match.state == MatchState.ENDED }
        assertTrue(ended, "bots never finished a 3-kill TDM match")

        val red = world.score.redScore
        val blue = world.score.blueScore
        assertTrue(red >= 3 || blue >= 3, "match ended below the kill limit: $red-$blue")

        // The results screen is up for POST_MATCH_SEC. Nothing may change during
        // it, otherwise the scoreboard contradicts the announced winner.
        simulateUntil(world, match, 5) { false }

        assertEquals(MatchState.ENDED, match.state, "still showing the results")
        assertEquals(red, world.score.redScore, "RED kept scoring after MATCH END")
        assertEquals(blue, world.score.blueScore, "BLUE kept scoring after MATCH END")
    }

    @Test
    fun `the next match starts clean and keeps the configured mode`() {
        val (world, match) = makeWorld(GameMode.TDM, 4)
        world.config.killLimit = 3

        assertTrue(
            simulateUntil(world, match, 180) { match.state == MatchState.ENDED },
            "bots never finished the first match",
        )
        assertTrue(
            simulateUntil(world, match, 30) { match.isActive },
            "the server never started the next match",
        )

        assertEquals(GameMode.TDM, world.mode, "the configured mode must survive a restart")
        assertEquals(0, world.score.redScore)
        assertEquals(0, world.score.blueScore)
    }
}
