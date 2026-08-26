package com.x3.tapshot.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Court is normalised: x -1..1 across, y 0 at the cannon and 1 at the top. */
const val COLS = 8
const val ROWS = 4

/** What a wave asks of the player. Each is borrowed from a game that proved it. */
const val GOAL_CLEAR = 0     // Space Invaders: destroy the formation
const val GOAL_DIVERS = 1    // Galaxian: survive and shoot the ones that break away
const val GOAL_RESCUE = 2    // Galaga: free the captured ships
const val GOAL_DEFEND = 3    // Missile Command: keep your shields alive
const val GOAL_BONUS = 4     // Galaga challenge stage: nobody shoots back

class Enemy(
    @JvmField var col: Int, @JvmField var row: Int,
    @JvmField var kind: Int,
    @JvmField var alive: Boolean = true,
    /** 0 = in formation, 1 = diving, 2 = returning. */
    @JvmField var state: Int = 0,
    @JvmField var x: Float = 0f, @JvmField var y: Float = 0f,
    @JvmField var t: Float = 0f,
    @JvmField var captive: Boolean = false
)

class Shot(@JvmField var x: Float, @JvmField var y: Float,
           @JvmField var vy: Float, @JvmField var mine: Boolean,
           @JvmField var live: Boolean = true)

sealed class Ev {
    class Killed(val x: Float, val y: Float, val kind: Int, val points: Int, val big: Boolean) : Ev()
    class Freed(val x: Float, val y: Float) : Ev()
    class ShieldHit(val idx: Int) : Ev()
    object Fired : Ev()
    object Dove : Ev()
    object Hurt : Ev()
    object WaveDone : Ev()
    object Over : Ev()
}

/**
 * ============================================================================
 *  A gallery shooter, in the lineage that already worked.
 * ============================================================================
 *
 * The cannon is locked to the bottom axis and slides; everything else comes
 * down at it. No Android, no GL — the rules only.
 *
 * ## Borrowed on purpose
 *
 * Each wave goal is lifted from a machine that proved it, rather than invented:
 *
 *  - CLEAR  (Space Invaders, 1978) — a formation that marches, drops a row at
 *    each wall, and **speeds up as it thins**. That acceleration is the single
 *    most copied mechanic in the genre, and it was originally a hardware
 *    accident: fewer sprites meant a faster frame. It survived because the
 *    tension curve it produces is perfect and nobody could design better.
 *  - DIVERS (Galaxian, 1979) — attackers peel out of the formation and swoop.
 *    Enemies that leave their grid is what separated Galaxian from Invaders.
 *  - RESCUE (Galaga, 1981) — a captured ship flies with the enemy; shoot its
 *    captor, not the captive, and you get it back. A goal that punishes
 *    indiscriminate fire is the most interesting thing in any of these games.
 *  - DEFEND (Missile Command, 1980) — you are not the thing being protected.
 *  - BONUS  (Galaga's challenge stage) — nobody shoots back and a perfect
 *    round pays. A breather every few waves is why people played for an hour.
 *
 * ## Not too intense, by construction
 *
 * Enemy fire is rate-limited per wave rather than per enemy, dives come one or
 * two at a time, and there is always a bonus wave in sight. The difficulty
 * comes from the formation speeding up, not from filling the screen.
 */
class Shooter(seed: Long = 0x54415053L) {

    private val rnd = Random(seed)

    @JvmField val enemies = ArrayList<Enemy>(COLS * ROWS)
    @JvmField val shots = ArrayList<Shot>(48)
    /** Shield blocks along the bottom; 0 = destroyed. */
    @JvmField val shields = IntArray(4) { 0 }

    var cannonX = 0f; private set
    var wave = 1; private set
    var score = 0; private set
    var lives = 3; private set
    var goal = GOAL_CLEAR; private set
    var over = false; private set
    var waveDone = false; private set

    /** Formation sweep. */
    private var formX = 0f
    private var formDir = 1f
    private var formY = 0f
    private var formSpeed = 0.10f
    private var baseSpeed = 0.10f

    private var fireTimer = 0f
    private var fireEvery = 1.6f
    private var diveTimer = 0f
    private var diveEvery = 3.0f
    private var myCooldown = 0f
    private var freed = 0
    private var toFree = 0
    private var hurtFlash = 0f

    private val evs = ArrayList<Ev>(16)

    fun aliveCount() = enemies.count { it.alive && !it.captive }
    fun captiveCount() = enemies.count { it.alive && it.captive }
    fun shieldsLeft() = shields.count { it > 0 }
    fun freedCount() = freed
    fun neededFree() = toFree

    /** New run: the wave setup does NOT reset these, so a run can span waves. */
    fun startFresh() {
        score = 0; lives = 3; over = false
    }

    fun startWave(w: Wave) {
        wave = w.n
        goal = w.goal
        baseSpeed = w.speed
        formSpeed = w.speed
        fireEvery = w.fireEvery
        diveEvery = w.diveEvery
        enemies.clear(); shots.clear()
        formX = 0f; formY = 0f; formDir = 1f
        fireTimer = 0f; diveTimer = 0f
        freed = 0; toFree = 0
        waveDone = false
        for (i in shields.indices) shields[i] = if (w.goal == GOAL_DEFEND) 3 else 0

        val rows = w.rows.coerceIn(1, ROWS)
        for (r in 0 until rows) for (c in 0 until COLS) {
            if (w.goal == GOAL_BONUS && (c + r) % 2 == 1) continue
            enemies.add(Enemy(c, r, kind = r % 3))
        }
        if (w.goal == GOAL_RESCUE) {
            // Two captives, never on the same column as each other.
            val cols = (0 until COLS).shuffled(rnd).take(2)
            for (c in cols) {
                enemies.firstOrNull { it.col == c && it.row == 0 }?.let { it.captive = true; toFree++ }
            }
        }
    }

    fun moveTo(x: Float) { cannonX = x.coerceIn(-0.92f, 0.92f) }

    fun fire(): Boolean {
        if (myCooldown > 0f || over || waveDone) return false
        // A cooldown rather than one-shot-on-screen: tapping faster should feel
        // faster, up to a limit, instead of being silently ignored.
        myCooldown = 0.16f
        shots.add(Shot(cannonX, 0.06f, 1.35f, true))
        evs.add(Ev.Fired)
        return true
    }

    fun update(dt: Float): List<Ev> {
        evs.clear()
        if (over || waveDone) return evs
        val h = if (dt.isNaN()) 0f else dt.coerceIn(0f, 0.05f)
        if (myCooldown > 0f) myCooldown -= h
        if (hurtFlash > 0f) hurtFlash -= h

        stepFormation(h)
        stepDives(h)
        stepEnemyFire(h)
        stepShots(h)
        checkGoal()
        return evs
    }

    /**
     * SPEEDS UP AS IT THINS — the Space Invaders curve. Interpolating on the
     * fraction remaining rather than a fixed step keeps it smooth on a wave of
     * eight as well as a wave of thirty-two.
     */
    private fun stepFormation(h: Float) {
        val total = enemies.size.coerceAtLeast(1)
        val living = enemies.count { it.alive }
        val thin = 1f - living.toFloat() / total
        formSpeed = baseSpeed * (1f + thin * 2.2f)

        var inFormation = false
        for (e in enemies) if (e.alive && e.state == 0) { inFormation = true; break }
        if (!inFormation) return

        formX += formDir * formSpeed * h
        val edge = 0.72f - colSpan() * 0.5f
        if (formX > edge) { formX = edge; formDir = -1f; formY += 0.055f }
        else if (formX < -edge) { formX = -edge; formDir = 1f; formY += 0.055f }

        // They reach you: that is a life, not an instant loss, so a bad wave is
        // recoverable.
        if (formBottom() < 0.10f) {
            hurt()
            formY = 0f
        }
    }

    private fun colSpan() = (COLS - 1) * 0.16f
    fun formationX(col: Int) = formX + (col - (COLS - 1) / 2f) * 0.16f
    fun formationY(row: Int) = 0.92f - formY - row * 0.13f
    private fun formBottom(): Float {
        var lowest = 1f
        for (e in enemies) if (e.alive && e.state == 0) {
            val y = formationY(e.row)
            if (y < lowest) lowest = y
        }
        return lowest
    }

    /** Galaxian's contribution: some of them leave the grid and come for you. */
    private fun stepDives(h: Float) {
        if (goal == GOAL_BONUS) return
        diveTimer += h
        if (diveTimer >= diveEvery) {
            diveTimer = 0f
            val cands = enemies.filter { it.alive && it.state == 0 && !it.captive }
            if (cands.isNotEmpty()) {
                val e = cands[rnd.nextInt(cands.size)]
                e.state = 1; e.t = 0f
                e.x = formationX(e.col); e.y = formationY(e.row)
                evs.add(Ev.Dove)
            }
        }
        for (e in enemies) {
            if (!e.alive || e.state != 1) continue
            e.t += h
            // A lazy S-curve toward the player's side, so a dive is dodgeable
            // by moving rather than by reacting instantly.
            val aim = cannonX * 0.8f
            e.y -= (0.42f + wave * 0.012f) * h
            e.x += (aim - e.x) * 0.9f * h + sin(e.t * 3.4f) * 0.35f * h
            if (e.y < -0.08f) {
                // Missed you and left; it rejoins rather than vanishing.
                e.state = 0
            }
        }
    }

    private fun stepEnemyFire(h: Float) {
        if (goal == GOAL_BONUS) return          // challenge stage: nobody shoots
        fireTimer += h
        if (fireTimer < fireEvery) return
        fireTimer = 0f
        // RATE LIMITED PER WAVE, not per enemy. Thirty enemies each deciding to
        // shoot is a bullet hell; the brief is a game you can enjoy without
        // clenching.
        val shooters = enemies.filter { it.alive && !it.captive && it.state == 0 }
        if (shooters.isEmpty()) return
        val e = shooters[rnd.nextInt(shooters.size)]
        shots.add(Shot(formationX(e.col), formationY(e.row) - 0.04f, -0.75f, false))
    }

    private fun stepShots(h: Float) {
        for (s in shots) {
            if (!s.live) continue
            s.y += s.vy * h
            if (s.y > 1.05f || s.y < -0.05f) { s.live = false; continue }
            if (s.mine) hitCheck(s) else playerCheck(s)
        }
        shots.removeAll { !it.live }
    }

    private fun hitCheck(s: Shot) {
        for (e in enemies) {
            if (!e.alive) continue
            val ex = if (e.state == 0) formationX(e.col) else e.x
            val ey = if (e.state == 0) formationY(e.row) else e.y
            if (abs(s.x - ex) > 0.062f || abs(s.y - ey) > 0.055f) continue

            if (e.captive) {
                // SHOOTING THE CAPTIVE IS THE MISTAKE THE WAVE IS ABOUT. It is
                // not punished with a life — it simply costs you the rescue,
                // which is the whole point of the goal.
                e.alive = false
                s.live = false
                evs.add(Ev.Killed(ex, ey, e.kind, 50, false))
                return
            }
            e.alive = false
            s.live = false
            val diving = e.state == 1
            val pts = (10 + e.kind * 10) * (if (diving) 2 else 1)
            score += pts
            evs.add(Ev.Killed(ex, ey, e.kind, pts, diving))

            // Galaga's rescue: killing the captor frees whatever it was holding.
            if (goal == GOAL_RESCUE) {
                val cap = enemies.firstOrNull { it.alive && it.captive && it.col == e.col }
                if (cap != null && e.row == cap.row + 1) {
                    cap.captive = false
                    freed++
                    score += 200
                    evs.add(Ev.Freed(formationX(cap.col), formationY(cap.row)))
                }
            }
            return
        }
    }

    private fun playerCheck(s: Shot) {
        // Shields first: that is what they are for.
        if (s.y in 0.10f..0.17f) {
            val idx = shieldIndex(s.x)
            if (idx >= 0 && shields[idx] > 0) {
                shields[idx]--
                s.live = false
                evs.add(Ev.ShieldHit(idx))
                return
            }
        }
        if (s.y < 0.075f && abs(s.x - cannonX) < 0.065f) {
            s.live = false
            hurt()
        }
    }

    fun shieldIndex(x: Float): Int {
        for (i in shields.indices) if (abs(x - shieldX(i)) < 0.11f) return i
        return -1
    }
    fun shieldX(i: Int) = -0.63f + i * 0.42f

    private fun hurt() {
        if (hurtFlash > 0f) return              // brief mercy, so one bad moment is one life
        hurtFlash = 1.2f
        lives--
        evs.add(Ev.Hurt)
        if (lives <= 0) { over = true; evs.add(Ev.Over) }
    }

    private fun checkGoal() {
        val done = when (goal) {
            GOAL_DEFEND -> aliveCount() == 0
            GOAL_RESCUE -> aliveCount() == 0
            else -> aliveCount() == 0
        }
        if (done && !waveDone) {
            waveDone = true
            evs.add(Ev.WaveDone)
        }
        if (goal == GOAL_DEFEND && shieldsLeft() == 0 && !waveDone && !over) {
            // Losing every shield ends the wave, but not the run.
            waveDone = true
            evs.add(Ev.WaveDone)
        }
    }
}
