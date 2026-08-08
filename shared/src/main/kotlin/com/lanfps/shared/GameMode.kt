package com.lanfps.shared

/** Supported match rulesets. */
enum class GameMode(val wire: Int) {
    /** Free-for-all deathmatch. */
    DM(0),

    /** Two-team deathmatch (RED vs BLUE). */
    TDM(1),

    /** One rail, one kill: everyone carries a bottomless one-shot sniper. */
    INSTAGIB(2);

    val isTeamBased: Boolean get() = this == TDM

    companion object {
        fun fromWire(v: Int): GameMode = when (v) {
            1 -> TDM
            2 -> INSTAGIB
            else -> DM
        }

        fun parse(text: String?): GameMode = when (text?.trim()?.uppercase()) {
            "TDM", "TEAM", "TEAMDEATHMATCH", "TEAM_DEATHMATCH" -> TDM
            "INSTAGIB", "IG", "GIB", "RAIL" -> INSTAGIB
            else -> DM
        }
    }
}
