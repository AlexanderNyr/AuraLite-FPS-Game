package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.GameMode
import com.lanfps.shared.InputCommand
import com.lanfps.shared.Team

/** A kill that still needs broadcasting to clients. */
class KillEvent(
    @JvmField val killerId: Int,
    @JvmField val killerName: String,
    @JvmField val victimId: Int,
    @JvmField val victimName: String,
)

/**
 * The authoritative game world: every entity, the simulation step, hit
 * resolution, damage, death and respawn.
 *
 * This class is the single source of truth. Clients only ever *ask* (via input)
 * and *observe* (via snapshots) — they never assert state.
 */
class World(
    @JvmField val serverArena: ServerArena,
    @JvmField val config: ServerConfig,
) {
    @JvmField var mode: GameMode = config.mode

    /**
     * Weapons are live only while a match is actually running.
     *
     * [GameServer] clears this during WARMUP and while the end-of-match results
     * are on screen. Everyone still walks around and respawns, so the world does
     * not look frozen, but nobody can score — otherwise the scoreboard behind
     * the results screen keeps ticking and contradicts the "BLUE wins 3-2" the
     * players were just shown.
     */
    @JvmField var combatEnabled: Boolean = true

    /** Insertion-ordered so snapshots have a stable entity order. */
    @JvmField val entities: LinkedHashMap<Int, GameEntity> = LinkedHashMap()

    @JvmField val physics: ServerPhysics = ServerPhysics(serverArena)
    @JvmField val raycast: ServerRaycast = ServerRaycast(serverArena.def)
    @JvmField val score: ScoreSystem = ScoreSystem()

    /** P1-1: past entity positions so a shot can be rewound to what the shooter
     *  actually saw (lag compensation). */
    @JvmField val history: PositionHistory = PositionHistory()

    private val botAI: BotAI = BotAI(this, serverArena, config.botDifficulty)

    /** Drained by [GameServer] each tick and sent as MATCH_EVENT packets. */
    @JvmField val killFeed: ArrayList<KillEvent> = ArrayList()

    private var nextBotIndex = 0
    private val botList = ArrayList<BotEntity>()

    val bots: List<BotEntity> get() = botList

    val players: List<PlayerEntity>
        get() = entities.values.filterIsInstance<PlayerEntity>()

    val playerCount: Int
        get() = entities.values.count { it is PlayerEntity }

    // ---- membership -------------------------------------------------------

    fun addPlayer(session: ClientSession): PlayerEntity {
        val player = PlayerEntity(session.id, session)
        player.team = assignTeam()
        entities[player.id] = player
        respawn(player)
        player.hasSpawned = true
        return player
    }

    fun removeEntity(id: Int) {
        val e = entities.remove(id)
        if (e is BotEntity) botList.remove(e)
    }

    /** Creates or removes bots so the world holds exactly [count] of them. */
    fun setBotCount(count: Int) {
        while (botList.size < count) {
            val id = GameConstants.BOT_ID_BASE + nextBotIndex
            nextBotIndex++
            val bot = BotEntity(id, botName(botList.size))
            bot.team = assignTeam()
            entities[bot.id] = bot
            botList.add(bot)
            respawn(bot)
        }
        while (botList.size > count) {
            val bot = botList.removeAt(botList.size - 1)
            entities.remove(bot.id)
        }
    }

    private fun botName(index: Int): String {
        val names = BOT_NAMES
        return if (index < names.size) names[index] else "Bot-${index + 1}"
    }

    /** Keeps the two TDM teams as even as possible. */
    fun assignTeam(): Team {
        if (!mode.isTeamBased) return Team.NONE
        var red = 0
        var blue = 0
        for (e in entities.values) {
            when (e.team) {
                Team.RED -> red++
                Team.BLUE -> blue++
                else -> {}
            }
        }
        return if (red <= blue) Team.RED else Team.BLUE
    }

    /** Re-assigns everyone when the mode changes between matches. */
    fun applyMode(newMode: GameMode) {
        if (mode == newMode) return
        mode = newMode
        var flip = false
        for (e in entities.values) {
            e.team = if (!newMode.isTeamBased) {
                Team.NONE
            } else {
                flip = !flip
                if (flip) Team.RED else Team.BLUE
            }
        }
        Log.info("game mode changed to ${newMode.name}; teams reassigned")
    }

    fun areAllies(a: GameEntity, b: GameEntity): Boolean {
        if (!mode.isTeamBased) return false
        if (a.team == Team.NONE || b.team == Team.NONE) return false
        return a.team == b.team
    }

    /** Everything the given entity is allowed to shoot. */
    private fun hostilesOf(e: GameEntity): List<GameEntity> =
        entities.values.filter { other ->
            other !== e && other.alive &&
                (GameConstants.FRIENDLY_FIRE || !areAllies(e, other))
        }

    private fun enemiesOf(e: GameEntity): List<GameEntity> =
        entities.values.filter { it !== e && !areAllies(e, it) }

    // ---- simulation -------------------------------------------------------

    /** Advances the whole world by exactly one fixed tick. */
    fun tick(dt: Float) {
        // 1) Human players: consume queued input.
        for (e in entities.values) {
            if (e !is PlayerEntity) continue
            val session = e.session
            session.refillTokens(dt)
            val cmd = session.nextCommand()
            e.firedThisTick = false
            if (e.fireCooldown > 0f) e.fireCooldown -= dt

            if (cmd != null && e.alive) {
                physics.step(e, cmd, dt)
                handleFire(e, cmd.firePressed)
            } else if (e.alive) {
                // No input available: keep gravity/physics running with no intent.
                IDLE_COMMAND.yaw = e.body.yaw
                IDLE_COMMAND.pitch = e.body.pitch
                IDLE_COMMAND.crouchPressed()
                physics.step(e, IDLE_COMMAND, dt)
            }
        }

        // 2) Bots: think, then run through identical physics.
        for (bot in botList) {
            botAI.update(bot, dt)
            bot.firedThisTick = false
            if (bot.fireCooldown > 0f) bot.fireCooldown -= dt
            if (bot.alive) {
                physics.step(bot, bot.input, dt)
                handleFire(bot, bot.input.firePressed)
            }
        }

        physics.separateBots(botList, dt)

        // 3) Respawns.
        for (e in entities.values) {
            if (e.alive) continue
            e.respawnTimer -= dt
            if (e.respawnTimer <= 0f) respawn(e)
        }

        // P1-1: record this tick's positions for lag compensation. Done at the
        // very end so history always reflects a completed tick.
        history.record(entities.values)
    }

    private fun handleFire(shooter: GameEntity, firePressed: Boolean) {
        if (!combatEnabled) return
        if (!firePressed || !shooter.alive) return
        if (shooter.fireCooldown > 0f) return

        shooter.fireCooldown = GameConstants.WEAPON_FIRE_INTERVAL
        shooter.firedThisTick = true

        val rewindTicks = lagCompRewindTicks(shooter)
        val rewindPositions =
            if (rewindTicks > 0) history.positionsAtTicksAgo(rewindTicks) else null
        val hit = raycast.fire(shooter, hostilesOf(shooter), rewindPositions = rewindPositions)
        val victim = hit.entity ?: return
        applyDamage(victim, shooter, GameConstants.WEAPON_DAMAGE)
    }

    /**
     * P1-1: how many simulation ticks back a shot should be tested. Targets are
     * drawn ~90 ms in the past plus half the shooter's round-trip time, capped at
     * [GameConstants.MAX_LAG_COMP_MS] so a lagging client gains no advantage.
     */
    private fun lagCompRewindTicks(shooter: GameEntity): Int {
        if (!config.lagCompensation) return 0
        val rtt = (shooter as? PlayerEntity)?.session?.smoothedRttMs ?: 0.0
        val rewindMs = GameConstants.INTERPOLATION_DELAY_MS + (rtt * 0.5).toInt()
        val capped = minOf(rewindMs, GameConstants.MAX_LAG_COMP_MS)
        return (capped / 1000.0 * GameConstants.TICK_RATE).toInt()
    }

    fun applyDamage(victim: GameEntity, attacker: GameEntity, amount: Int) {
        if (!victim.alive) return
        victim.health -= amount
        victim.lastAttackerId = attacker.id

        if (victim.health > 0) return

        victim.kill()
        if (attacker !== victim) {
            attacker.kills++
            score.registerKill(attacker, mode)
        }
        killFeed.add(KillEvent(attacker.id, attacker.name, victim.id, victim.name))
        Log.info("KILL ${attacker.name} -> ${victim.name} (${scoreSummary()})")
    }

    fun respawn(entity: GameEntity) {
        val spawn = serverArena.pickSpawn(entity.team, enemiesOf(entity))
        entity.spawnAt(spawn)
    }

    /** Full reset between matches: scores cleared, everyone respawned. */
    fun resetForNewMatch() {
        score.reset()
        for (e in entities.values) {
            e.kills = 0
            e.deaths = 0
            respawn(e)
        }
        killFeed.clear()
    }

    fun scoreSummary(): String = when {
        mode.isTeamBased -> "RED ${score.redScore} - BLUE ${score.blueScore}"
        else -> entities.values
            .sortedByDescending { it.kills }
            .take(3)
            .joinToString(", ") { "${it.name}:${it.kills}" }
    }

    companion object {
        private val BOT_NAMES = arrayOf(
            "Vega", "Orion", "Lyra", "Rigel", "Nova", "Draco",
            "Pyxis", "Corvus", "Mira", "Altair", "Cygnus", "Fenix",
            "Sirius", "Antares", "Polaris", "Vela",
        )

        /** Shared "no intent" command used when a client's input queue is empty. */
        private val IDLE_COMMAND = InputCommand()

        private fun InputCommand.crouchPressed() {
            moveForward = 0f
            moveRight = 0f
            buttons = 0
        }
    }
}
