package com.x3.tapshot.game

class Wave(
    @JvmField val n: Int,
    @JvmField val name: String,
    @JvmField val goal: Int,
    @JvmField val rows: Int,
    @JvmField val speed: Float,
    @JvmField val fireEvery: Float,
    @JvmField val diveEvery: Float,
    @JvmField val music: Int,
    @JvmField val blurb: String
)

/**
 * Six waves, cycling. Each goal is borrowed from a machine that proved it, and
 * they alternate so no two consecutive waves ask the same thing.
 *
 * THE CURVE IS GENTLE ON PURPOSE — "colourful and fun, not too intense". The
 * formation still accelerates as it thins (the Space Invaders curve, which is
 * where the tension actually comes from), but enemy fire stays rate-limited and
 * a bonus wave arrives every sixth round as a breather. Difficulty grows by
 * making the board move faster, never by filling it with bullets.
 */
object Waves {
    val all: List<Wave> = listOf(
        Wave(1, "LAUNCH",  GOAL_CLEAR,  2, 0.085f, 2.30f, 4.5f, 0, "CLEAR THE FORMATION"),
        Wave(2, "ORBIT",   GOAL_DIVERS, 3, 0.100f, 2.00f, 2.8f, 1, "THEY BREAK FORMATION"),
        Wave(3, "SWARM",   GOAL_CLEAR,  3, 0.115f, 1.75f, 2.4f, 2, "FASTER AS IT THINS"),
        Wave(4, "RESCUE",  GOAL_RESCUE, 3, 0.105f, 1.90f, 3.0f, 3, "FREE THE CAPTIVES"),
        Wave(5, "SIEGE",   GOAL_DEFEND, 4, 0.120f, 1.45f, 2.6f, 4, "HOLD YOUR SHIELDS"),
        Wave(6, "BONUS",   GOAL_BONUS,  3, 0.190f, 9.99f, 9.9f, 5, "NOBODY SHOOTS BACK")
    )

    val count get() = all.size

    fun get(n: Int): Wave {
        val i = n.coerceAtLeast(1)
        val lap = (i - 1) / count
        val base = all[(i - 1) % count]
        if (lap == 0) return base
        // Past the first loop the board moves faster and fires a little sooner,
        // capped so it never becomes the bullet hell the brief rules out.
        val k = 1f + 0.16f * lap
        return Wave(
            n = i, name = base.name, goal = base.goal, rows = base.rows,
            speed = (base.speed * k).coerceAtMost(0.34f),
            fireEvery = (base.fireEvery / k).coerceAtLeast(0.95f),
            diveEvery = (base.diveEvery / k).coerceAtLeast(1.3f),
            music = base.music, blurb = base.blurb
        )
    }
}
