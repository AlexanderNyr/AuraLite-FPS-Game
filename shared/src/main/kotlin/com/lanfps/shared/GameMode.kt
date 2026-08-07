package com.lanfps.shared

/** Supported match rulesets. */
enum class GameMode(val wire: Int) {
    /** Free-for-all deathmatch. */
    DM(0),

    /** Two-team deathmatch (RED vs BLUE). */
    TDM(1);

    val isTeamBased: Boolean get() = this == TDM

    companion object {
        fun fromWire(v: Int): GameMode = if (v == 1) TDM else DM

        fun parse(text: String?): GameMode = when (text?.trim()?.uppercase()) {
            "TDM", "TEAM", "TEAMDEATHMATCH", "TEAM_DEATHMATCH" -> TDM
            else -> DM
        }
    }
}
