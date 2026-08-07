package com.lanfps.server

import com.lanfps.shared.EntityType

/**
 * A human player. Owns nothing but a link to its [ClientSession]; all movement
 * comes from the session's input queue, so the server never trusts a position
 * sent by the client.
 */
class PlayerEntity(
    id: Int,
    @JvmField val session: ClientSession,
) : GameEntity(id) {

    override val entityType: Int get() = EntityType.PLAYER

    init {
        name = session.nickname
    }

    /** True once the player has been spawned into the match at least once. */
    @JvmField var hasSpawned: Boolean = false
}
