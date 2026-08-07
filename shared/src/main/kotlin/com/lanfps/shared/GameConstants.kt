package com.lanfps.shared

/**
 * Single source of truth for every tunable shared by the server simulation and the
 * client prediction. Client and server MUST agree on these values or prediction
 * will constantly mispredict.
 */
object GameConstants {

    // ---- Protocol ----------------------------------------------------------
    /** ASCII "LANF" — first 4 bytes of every packet. */
    const val MAGIC: Int = 0x4C414E46
    const val PROTOCOL_VERSION: Int = 1

    const val DEFAULT_UDP_PORT: Int = 7777
    const val DEFAULT_TCP_PORT: Int = 7778
    const val DEFAULT_SERVER_IP: String = "192.168.1.25"

    /** Hard cap for a single datagram we are willing to build or accept. */
    const val MAX_PACKET_SIZE: Int = 10 * 1024

    // ---- Timing ------------------------------------------------------------
    /** Authoritative simulation rate. */
    const val TICK_RATE: Int = 60
    const val TICK_DT: Float = 1.0f / TICK_RATE
    const val TICK_NANOS: Long = 1_000_000_000L / TICK_RATE

    /** Snapshot broadcast rate. */
    const val SNAPSHOT_RATE: Int = 30
    const val SNAPSHOT_INTERVAL_NANOS: Long = 1_000_000_000L / SNAPSHOT_RATE

    /** Remote entities are rendered this far in the past so we always have two
     *  snapshots to interpolate between. ~2.5 snapshots at 30 Hz. */
    const val INTERPOLATION_DELAY_MS: Int = 90

    /** Client stops hearing from server for this long -> assume disconnected. */
    const val CLIENT_TIMEOUT_MS: Long = 5_000
    /** Server stops hearing from a client for this long -> drop the session. */
    const val SERVER_TIMEOUT_MS: Long = 8_000

    /** Client sends a ping this often. */
    const val PING_INTERVAL_MS: Long = 1_000

    /** Anti-cheat: a client may not have more than this many input commands
     *  processed per second (fixed-step inputs, 60/s is nominal). */
    const val MAX_INPUTS_PER_SECOND: Int = 90

    /** How many past input commands are re-sent in each input packet for
     *  redundancy against UDP loss. */
    const val INPUT_REDUNDANCY: Int = 3

    // ---- Player ------------------------------------------------------------
    const val MAX_HEALTH: Int = 100
    const val PLAYER_RADIUS: Float = 0.35f
    const val PLAYER_HEIGHT: Float = 1.8f
    const val PLAYER_CROUCH_HEIGHT: Float = 1.1f
    const val EYE_HEIGHT: Float = 1.62f
    const val EYE_HEIGHT_CROUCH: Float = 0.95f

    const val MOVE_SPEED: Float = 5.4f
    const val CROUCH_SPEED: Float = 2.4f
    const val AIR_CONTROL: Float = 0.35f
    const val ACCELERATION: Float = 14.0f
    const val FRICTION: Float = 10.0f
    const val GRAVITY: Float = -20.0f
    const val JUMP_VELOCITY: Float = 6.6f

    const val MAX_PITCH_DEG: Float = 89.0f

    // ---- Weapon ------------------------------------------------------------
    const val WEAPON_DAMAGE: Int = 25
    const val WEAPON_RANGE: Float = 120.0f
    /** Seconds between shots (≈ 480 RPM). */
    const val WEAPON_FIRE_INTERVAL: Float = 0.125f
    /** First version: infinite ammo, HUD shows the infinity glyph. */
    const val WEAPON_INFINITE_AMMO: Boolean = true

    // ---- Match -------------------------------------------------------------
    const val RESPAWN_DELAY_SEC: Float = 3.0f
    const val DEFAULT_MATCH_TIME_SEC: Int = 300
    const val DEFAULT_KILL_LIMIT: Int = 20
    const val DEFAULT_MAX_PLAYERS: Int = 8
    const val DEFAULT_BOT_COUNT: Int = 4
    /** Seconds the results screen is shown before the next match starts. */
    const val POST_MATCH_SEC: Float = 12.0f

    const val FRIENDLY_FIRE: Boolean = false

    // ---- Identity ----------------------------------------------------------
    const val MAX_NICKNAME_LENGTH: Int = 16
    const val ARENA_NAME: String = "arena01"

    /** Entity ids >= this belong to bots. Keeps id spaces from colliding. */
    const val BOT_ID_BASE: Int = 1000
}
