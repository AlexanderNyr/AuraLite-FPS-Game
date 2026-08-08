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

    /** Bumped to 3 (P2/P3 of the improvement plan): EntityState gained weapon +
     *  ammo bytes, CONNECT_REQUEST gained a password field, LOBBY_STATE gained
     *  killLimit + mode votes, and the MODE_VOTE packet type was added. */
    const val PROTOCOL_VERSION: Int = 3

    const val DEFAULT_UDP_PORT: Int = 7777
    const val DEFAULT_TCP_PORT: Int = 7778
    const val DEFAULT_SERVER_IP: String = "192.168.1.25"

    /** Hard cap for a single datagram we are willing to build or accept. */
    const val MAX_PACKET_SIZE: Int = 10 * 1024

    /**
     * P0-1: an Ethernet/Wi-Fi UDP datagram fragments beyond ~1472 bytes
     * (1500 MTU - 20 IP - 8 UDP), and a single lost fragment destroys the whole
     * snapshot. Every snapshot must stay under this MTU-safe budget.
     */
    const val SNAPSHOT_MAX_BYTES: Int = 1400

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
    /** Server stops hearing from a client for this long -> mark the session a
     *  zombie (kept for reconnect) rather than dropping it. */
    const val SERVER_TIMEOUT_MS: Long = 8_000

    /** P0-2: how long a silent session is kept as a zombie for a reconnect with
     *  its resume token before the entity and slot are finally reclaimed. */
    const val ZOMBIE_TIMEOUT_MS: Long = 30_000

    /** P0-2: how long the client keeps trying to reconnect before giving up. */
    const val RECONNECT_TIMEOUT_MS: Long = 30_000

    /** Client sends a ping this often. */
    const val PING_INTERVAL_MS: Long = 1_000

    /** Anti-cheat: a client may not have more than this many input commands
     *  processed per second (fixed-step inputs, 60/s is nominal). */
    const val MAX_INPUTS_PER_SECOND: Int = 90

    // ---- P0-3: connection flood protection -------------------------------
    /** Max brand-new sessions the server accepts per second, globally. */
    const val MAX_CONNECTS_PER_SECOND: Int = 5
    /** Max new sessions accepted per second from a single source IP. */
    const val MAX_CONNECTS_PER_IP_SECOND: Int = 2
    /** Max simultaneously active (non-zombie) sessions from one source IP.
     *  Two phones behind one NAT is already rare; this stops a flood script. */
    const val MAX_SESSIONS_PER_IP: Int = 2

    /** How many past input commands are re-sent in each input packet for
     *  redundancy against UDP loss. */
    /**
     * Newest N-1 commands re-sent with every input packet. One lost datagram is
     * fully healed by the next one; bursts of 2-4 losses (typical Wi-Fi flap)
     * used to force the server into starvation-extrapolation and then hit the
     * client with a correction the moment the queue healed — the "teleport"
     * players report as high ping. 6 keeps ~100 ms of cover for ~150 bytes of
     * extra upstream traffic per second (negligible even on 2.4 GHz Wi-Fi).
     */
    const val INPUT_REDUNDANCY: Int = 6

    // ---- P1-4: reliable match events -------------------------------------
    /** Hard cap on unacknowledged MATCH_EVENTs held per session. */
    const val MAX_PENDING_MATCH_EVENTS: Int = 64

    // ---- P1-1: lag compensation ------------------------------------------
    /** Upper bound on how far (ms) the server rewinds targets on a shot, so a
     *  badly lagging client gains no advantage. */
    const val MAX_LAG_COMP_MS: Int = 250

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
    // Weapon numbers (damage, rate of fire, magazine, spread, range, recoil)
    // live in [Weapons] / [WeaponDef] — P2-1 replaced the single hard-coded
    // rifle with a catalogue. Finite vs infinite ammo is a server config key
    // (`infiniteAmmo` in server.properties), not a constant.

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
