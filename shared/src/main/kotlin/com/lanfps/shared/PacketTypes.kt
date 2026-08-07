package com.lanfps.shared

/** Wire packet type ids. Stored as a single unsigned byte in the header. */
object PacketTypes {
    const val DISCOVERY_REQUEST: Int = 1
    const val DISCOVERY_RESPONSE: Int = 2
    const val CONNECT_REQUEST: Int = 3
    const val CONNECT_ACCEPTED: Int = 4
    const val CONNECT_REJECTED: Int = 5
    const val CLIENT_INPUT: Int = 6
    const val SERVER_SNAPSHOT: Int = 7
    const val PING: Int = 8
    const val PONG: Int = 9
    const val DISCONNECT: Int = 10
    const val LOBBY_STATE: Int = 11
    const val MATCH_EVENT: Int = 12

    fun name(type: Int): String = when (type) {
        DISCOVERY_REQUEST -> "DISCOVERY_REQUEST"
        DISCOVERY_RESPONSE -> "DISCOVERY_RESPONSE"
        CONNECT_REQUEST -> "CONNECT_REQUEST"
        CONNECT_ACCEPTED -> "CONNECT_ACCEPTED"
        CONNECT_REJECTED -> "CONNECT_REJECTED"
        CLIENT_INPUT -> "CLIENT_INPUT"
        SERVER_SNAPSHOT -> "SERVER_SNAPSHOT"
        PING -> "PING"
        PONG -> "PONG"
        DISCONNECT -> "DISCONNECT"
        LOBBY_STATE -> "LOBBY_STATE"
        MATCH_EVENT -> "MATCH_EVENT"
        else -> "UNKNOWN($type)"
    }
}

/** Input button bitmask carried in every InputCommand. */
object InputButtons {
    const val FIRE: Int = 1 shl 0
    const val JUMP: Int = 1 shl 1
    const val CROUCH: Int = 1 shl 2
    const val RELOAD: Int = 1 shl 3
}

/** MATCH_EVENT sub-types. */
object MatchEventType {
    const val KILL: Int = 1
    const val MATCH_START: Int = 2
    const val MATCH_END: Int = 3
    const val PLAYER_JOINED: Int = 4
    const val PLAYER_LEFT: Int = 5
}

/** High-level match phase, mirrored to clients in every snapshot. */
object MatchState {
    const val WARMUP: Int = 0
    const val ACTIVE: Int = 1
    const val ENDED: Int = 2

    fun name(state: Int): String = when (state) {
        WARMUP -> "WARMUP"
        ACTIVE -> "ACTIVE"
        ENDED -> "ENDED"
        else -> "UNKNOWN"
    }
}

/** Entity kinds encoded in snapshots. */
object EntityType {
    const val PLAYER: Int = 0
    const val BOT: Int = 1
}
