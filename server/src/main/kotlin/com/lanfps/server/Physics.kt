package com.lanfps.server

import com.lanfps.shared.GameConstants
import com.lanfps.shared.InputCommand
import com.lanfps.shared.MovementSolver
import com.lanfps.shared.Vec3

/**
 * Server-side physics façade.
 *
 * The actual character movement lives in [MovementSolver] inside `shared`,
 * because the Android client has to run the *identical* code to predict its own
 * movement. This class adds the parts only the server needs.
 *
 * Deliberate design choice: **there is no player-vs-player collision.** The
 * client predicts against level geometry only; if the server also pushed players
 * apart, every crowded moment would produce a prediction error and a visible
 * rubber-band. Bots are pushed apart (see [separateBots]) because nobody
 * predicts them.
 */
class ServerPhysics(private val arena: ServerArena) {

    private val solver = MovementSolver()
    private val push = Vec3()

    /** Advances one entity by a single fixed tick. */
    fun step(entity: GameEntity, cmd: InputCommand, dt: Float = GameConstants.TICK_DT) {
        solver.step(entity.body, cmd, arena.def, dt)
    }

    fun fits(pos: Vec3): Boolean = solver.fits(pos, arena.def)

    /**
     * Gentle mutual repulsion so bots do not pile into one another.
     * Only applied between bots, never to a predicted player.
     */
    fun separateBots(bots: List<BotEntity>, dt: Float) {
        val minDist = GameConstants.PLAYER_RADIUS * 2f
        for (i in bots.indices) {
            val a = bots[i]
            if (!a.alive) continue
            for (j in i + 1 until bots.size) {
                val b = bots[j]
                if (!b.alive) continue
                val dx = b.body.position.x - a.body.position.x
                val dz = b.body.position.z - a.body.position.z
                val distSq = dx * dx + dz * dz
                if (distSq >= minDist * minDist || distSq < 1e-6f) continue

                val dist = kotlin.math.sqrt(distSq)
                val overlap = (minDist - dist) * 0.5f
                push.set(dx / dist, 0f, dz / dist)

                val step = overlap * SEPARATION_STRENGTH * dt * 60f
                tryNudge(a, -push.x * step, -push.z * step)
                tryNudge(b, push.x * step, push.z * step)
            }
        }
    }

    /** Moves an entity only if the destination is not inside geometry. */
    private fun tryNudge(e: GameEntity, dx: Float, dz: Float) {
        val p = e.body.position
        val oldX = p.x
        val oldZ = p.z
        p.x += dx
        p.z += dz
        if (!solver.fits(p, arena.def, e.body.height)) {
            p.x = oldX
            p.z = oldZ
        }
    }

    companion object {
        private const val SEPARATION_STRENGTH = 0.5f
    }
}
