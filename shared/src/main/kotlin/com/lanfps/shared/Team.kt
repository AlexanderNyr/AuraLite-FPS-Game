package com.lanfps.shared

/** Team affiliation. NONE is used in Deathmatch where everyone is an enemy. */
enum class Team(val wire: Int) {
    NONE(0),
    RED(1),
    BLUE(2);

    companion object {
        fun fromWire(v: Int): Team = when (v) {
            1 -> RED
            2 -> BLUE
            else -> NONE
        }
    }
}
