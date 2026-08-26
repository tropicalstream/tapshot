package com.x3.tapshot.game

import com.x3.tapshot.Settings
import com.x3.tapshot.audio.GameAudio
import com.x3.tapshot.input.SwipeControl
import com.x3.tapshot.render.NeonBatch
import com.x3.tapshot.render.Particles
import com.x3.tapshot.render.VectorFont
import kotlin.math.cos
import kotlin.math.sin

/**
 * ============================================================================
 *  TAPSHOT — a gallery shooter for the RayNeo X3 Pro.
 * ============================================================================
 *
 * ## Two inputs, and that is the whole game
 *
 *     drag   slide the cannon along the bottom
 *     tap    fire
 *
 * The cannon follows the pad's absolute calibrated position, so your finger IS
 * the cannon — the same reason Pong could not use flicks. Firing is a tap, and
 * a tap is all it is.
 *
 * NOTHING IS BOUND TO A DOUBLE TAP. Two shots in quick succession are the most
 * ordinary thing a player does in a shooter; anything bound to a double tap
 * would fire while they were simply shooting twice.
 *
 * ## Colourful, and deliberately not intense
 *
 * Enemy fire is rate-limited for the whole wave rather than per enemy, dives
 * arrive one at a time, and every sixth wave nobody shoots back at all. The
 * pressure comes from the formation accelerating as it thins — the Space
 * Invaders curve, which was a hardware accident in 1978 and has never been
 * bettered — rather than from filling the screen with bullets.
 */
class Game(
    private val settings: Settings,
    private val audio: GameAudio,
    private val swipe: SwipeControl
) {

    val batch = NeonBatch()
    @JvmField var fps: Float = 0f
    @JvmField var tempC: Int = 0

    private companion object {
        const val ISO_TILT = 0.18f          // a little depth; a shooter needs a straight axis
        const val HALF_W = 0.1250f
        const val BOT_V = -0.1000f
        const val TOP_V = 0.1000f

        const val ST_MENU = 0
        const val ST_PLAY = 1
        const val ST_WAVE = 2
        const val ST_OVER = 3

        const val ROW_RELAX = 0
        const val ROW_START = 1
        const val ROWS_MENU = 2
        const val ROW_GAP_MS = 150L
    }

    private val game = Shooter()
    private val parts = Particles(512)

    private var state = ST_MENU
    private var stateT = 0f
    private var row = ROW_START
    private var lastRowMs = 0L
    private var waveNo = 1
    private var wave = Waves.get(1)
    private var musicPlaying = -1
    private var shake = 0f
    private var flash = 0f

    private val events = ArrayList<Char>(8)
    private val windowScales = floatArrayOf(70f, 92f, 115f)

    /** Bright, distinct, and readable through glass with a room behind it. */
    private val kindR = floatArrayOf(0.30f, 1.00f, 1.00f)
    private val kindG = floatArrayOf(1.00f, 0.45f, 0.90f)
    private val kindB = floatArrayOf(0.95f, 0.95f, 0.25f)

    init { parts.scaleLengths(0.013f) }

    // ---- input ------------------------------------------------------------

    fun tap() { synchronized(events) { events.add('T') } }
    /** Deliberately inert — see the class note. */
    fun doubleTap() { }
    fun swipe(steps: Int) { synchronized(events) { events.add(if (steps > 0) '+' else '-') } }

    private fun drainInput() {
        synchronized(events) {
            for (e in events) when (e) {
                '+' -> onSwipe(1)
                '-' -> onSwipe(-1)
                'T' -> onTap()
            }
            events.clear()
        }
    }

    private fun onSwipe(dir: Int) {
        if (state != ST_MENU) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastRowMs < ROW_GAP_MS) return
        lastRowMs = now
        row = ((row + dir) % ROWS_MENU + ROWS_MENU) % ROWS_MENU
        audio.sfx("ui")
    }

    private fun onTap() {
        when (state) {
            ST_MENU -> if (row == ROW_RELAX) {
                settings.relaxed = !settings.relaxed; audio.sfx("ui")
            } else startRun()
            ST_PLAY -> if (game.fire()) audio.sfx("shot")
            ST_WAVE -> startWave(waveNo + 1)
            else -> { state = ST_MENU; stateT = 0f; row = ROW_START; audio.sfx("ui"); stopMusic() }
        }
    }

    /** BACK: out of a run. The one input the gameplay cannot produce. */
    fun back(): Boolean = when (state) {
        ST_PLAY, ST_WAVE -> { stopMusic(); state = ST_MENU; stateT = 0f; row = ROW_START; true }
        ST_OVER -> { state = ST_MENU; stateT = 0f; row = ROW_START; true }
        else -> false
    }

    // ---- flow -------------------------------------------------------------

    private fun startRun() {
        waveNo = 1
        startWave(1, fresh = true)
    }

    private fun startWave(n: Int, fresh: Boolean = false) {
        waveNo = n
        wave = Waves.get(n)
        if (fresh) game.startFresh()
        // RELAXED IS APPLIED HERE, not baked into the table, so the same six
        // waves serve both paces and there is only one curve to reason about.
        game.startWave(if (settings.relaxed) Wave(
            n = wave.n, name = wave.name, goal = wave.goal, rows = wave.rows,
            speed = wave.speed * 0.72f,
            fireEvery = wave.fireEvery * 1.5f,
            diveEvery = wave.diveEvery * 1.6f,
            music = wave.music, blurb = wave.blurb
        ) else wave)
        parts.clear()
        state = ST_PLAY
        stateT = 0f
        shake = 0f
        if (musicPlaying != wave.music) {
            audio.playLevelMusic(wave.music + 1)
            musicPlaying = wave.music
        }
        audio.sfx("wave")
    }

    private fun stopMusic() {
        if (musicPlaying >= 0) { audio.stopLevelMusic(); musicPlaying = -1 }
    }

    // ---- frame ------------------------------------------------------------

    fun update(dt: Float) {
        stateT += dt
        drainInput()

        if (state == ST_PLAY) {
            // Absolute pad position drives the cannon; 0 is one end of the
            // calibrated sweep, 1 the other.
            game.moveTo((swipe.pos01 * 2f - 1f))
            step(dt)
        }

        parts.update(dt)
        if (shake > 0f) shake = (shake - dt * 2.4f).coerceAtLeast(0f)
        if (flash > 0f) flash = (flash - dt * 2.2f).coerceAtLeast(0f)

        val s = windowScales[settings.windowSize.coerceIn(0, 2)]
        val ct = cos(ISO_TILT); val st = sin(ISO_TILT)
        batch.setBasis(0f, 0f, 0f, s, 0f, 0f, 0f, s * ct, -s * st, 0f, s * st, s * ct)
        batch.lift = 0f

        batch.begin()
        when (state) {
            ST_MENU -> drawMenu()
            ST_PLAY -> { drawField(); drawHud() }
            ST_WAVE -> { drawField(); drawHud(); drawWaveEnd() }
            else -> { drawField(); drawOver() }
        }
        parts.draw(batch)
    }

    private fun step(dt: Float) {
        for (e in game.update(dt)) when (e) {
            is Ev.Fired -> Unit
            is Ev.Dove -> audio.sfx("dive")
            is Ev.Killed -> {
                audio.sfx(if (e.big) "hit_big" else "hit")
                val k = e.kind.coerceIn(0, 2)
                parts.burst(fx(e.x), fy(e.y), if (e.big) 26 else 16, 0.07f,
                    kindR[k], kindG[k], kindB[k], 0.7f)
                if (e.big) shake = 0.35f
            }
            is Ev.Freed -> {
                audio.sfx("rescue")
                parts.burst(fx(e.x), fy(e.y), 34, 0.08f, 0.4f, 1f, 0.8f, 1.0f)
                flash = 0.8f
            }
            is Ev.ShieldHit -> {
                audio.sfx("enemy")
                parts.burst(fx(game.shieldX(e.idx)), fy(0.135f), 10, 0.045f, 0.5f, 0.8f, 1f, 0.4f)
            }
            is Ev.Hurt -> { audio.sfx("hurt"); shake = 1f; parts.burst(fx(game.cannonX), fy(0.04f), 40, 0.09f, 1f, 0.4f, 0.2f, 0.9f) }
            is Ev.WaveDone -> {
                val perfect = wave.goal == GOAL_BONUS && game.aliveCount() == 0
                audio.sfx(if (perfect) "perfect" else "wave")
                state = ST_WAVE
                stateT = 0f
            }
            is Ev.Over -> { audio.sfx("hurt"); stopMusic(); state = ST_OVER; stateT = 0f }
        }
    }

    // ---- geometry ---------------------------------------------------------

    private fun fx(x: Float) = x * HALF_W + shakeU()
    private fun fy(y: Float) = BOT_V + y * (TOP_V - BOT_V) + shakeV()
    private fun shakeU() = if (shake <= 0f) 0f else sin(stateT * 57f) * 0.0018f * shake
    private fun shakeV() = if (shake <= 0f) 0f else cos(stateT * 41f) * 0.0018f * shake

    // ---- drawing ----------------------------------------------------------

    private fun drawField() {
        // Frame: just the floor the cannon rides and a faint ceiling, so the
        // play area has bounds without boxing the room in.
        batch.line(-HALF_W, fy(0f), HALF_W, fy(0f), 0.0012f, 0.4f, 0.85f, 1f, 0.7f)
        batch.line(-HALF_W, fy(1.02f), HALF_W, fy(1.02f), 0.0006f, 0.3f, 0.6f, 1f, 0.18f)

        // Shields.
        for (i in 0 until 4) {
            val hp = game.shields[i]
            if (hp <= 0) continue
            val u = fx(game.shieldX(i))
            val v = fy(0.135f)
            val a = 0.35f + hp * 0.2f
            val w = 0.016f
            batch.fill(u, v, w, 0.005f, 0f, 0.2f, 0.8f, 0.6f, a * 0.5f)
            batch.line(u - w, v - 0.005f, u - w, v + 0.005f, 0.0009f, 0.35f, 1f, 0.8f, a)
            batch.line(u + w, v - 0.005f, u + w, v + 0.005f, 0.0009f, 0.35f, 1f, 0.8f, a)
            batch.line(u - w, v + 0.005f, u + w, v + 0.005f, 0.0011f, 0.35f, 1f, 0.8f, a)
        }

        // Enemies.
        for (e in game.enemies) {
            if (!e.alive) continue
            val ex = if (e.state == 0) game.formationX(e.col) else e.x
            val ey = if (e.state == 0) game.formationY(e.row) else e.y
            drawEnemy(fx(ex), fy(ey), e.kind, e.captive, e.state == 1)
        }

        // Shots.
        for (s in game.shots) {
            if (!s.live) continue
            val u = fx(s.x); val v = fy(s.y)
            if (s.mine) {
                batch.line(u, v - 0.006f, u, v + 0.006f, 0.0013f, 0.6f, 1f, 1f, 0.95f)
            } else {
                batch.line(u, v - 0.006f, u, v + 0.006f, 0.0012f, 1f, 0.55f, 0.25f, 0.9f)
            }
        }

        drawCannon()
    }

    /**
     * Three silhouettes, not three colours of the same box: at a glance you
     * must know what is in front of you, and colour alone fails the moment two
     * kinds overlap.
     */
    private fun drawEnemy(u: Float, v: Float, kind: Int, captive: Boolean, diving: Boolean) {
        val k = kind.coerceIn(0, 2)
        var r = kindR[k]; var g = kindG[k]; var b = kindB[k]
        if (captive) { r = 0.45f; g = 1f; b = 0.85f }
        val a = if (diving) 1f else 0.92f
        val w = 0.011f; val h = 0.008f
        batch.fill(u, v, w * 0.7f, h * 0.6f, 0f, r * 0.45f, g * 0.45f, b * 0.45f, a * 0.7f)
        when (k) {
            0 -> { // wings out
                batch.line(u - w, v, u - w * 0.3f, v + h, 0.0009f, r, g, b, a)
                batch.line(u + w, v, u + w * 0.3f, v + h, 0.0009f, r, g, b, a)
                batch.line(u - w, v, u + w, v, 0.0009f, r, g, b, a)
            }
            1 -> { // squat blocker
                batch.line(u - w, v - h, u + w, v - h, 0.0009f, r, g, b, a)
                batch.line(u - w, v + h, u + w, v + h, 0.0009f, r, g, b, a)
                batch.line(u - w, v - h, u - w, v + h, 0.0009f, r, g, b, a)
                batch.line(u + w, v - h, u + w, v + h, 0.0009f, r, g, b, a)
            }
            else -> { // diamond
                batch.line(u, v + h, u + w, v, 0.0009f, r, g, b, a)
                batch.line(u + w, v, u, v - h, 0.0009f, r, g, b, a)
                batch.line(u, v - h, u - w, v, 0.0009f, r, g, b, a)
                batch.line(u - w, v, u, v + h, 0.0009f, r, g, b, a)
            }
        }
        if (captive) {
            // A captive is worth more alive; ring it so nobody shoots it by accident.
            batch.circle(u, v, 0.016f, 0.0007f, 0.4f, 1f, 0.9f,
                0.35f + 0.25f * sin(stateT * 5f))
        }
    }

    private fun drawCannon() {
        val u = fx(game.cannonX)
        val v = fy(0.035f)
        val w = 0.013f
        batch.fill(u, v, w * 0.55f, 0.004f, 0f, 0.3f, 0.9f, 0.5f, 0.7f)
        batch.line(u - w, v - 0.004f, u + w, v - 0.004f, 0.0012f, 0.5f, 1f, 0.7f, 1f)
        batch.line(u - w, v - 0.004f, u - w * 0.4f, v + 0.004f, 0.0011f, 0.5f, 1f, 0.7f, 1f)
        batch.line(u + w, v - 0.004f, u + w * 0.4f, v + 0.004f, 0.0011f, 0.5f, 1f, 0.7f, 1f)
        batch.line(u, v + 0.004f, u, v + 0.011f, 0.0013f, 0.7f, 1f, 0.9f, 1f)
    }

    private fun drawHud() {
        VectorFont.draw(batch, "${game.score}", -0.132f, 0.104f, 0.0024f, 0.6f, 0.95f, 1f, 0.85f)
        VectorFont.draw(batch, "W$waveNo ${wave.name}", 0.020f, 0.108f, 0.0022f, 1f, 0.6f, 0.9f, 0.75f)
        var i = 0
        while (i < game.lives) {
            batch.circle(-0.132f + i * 0.009f, 0.092f, 0.0024f, 0.0007f, 0.4f, 1f, 0.7f, 0.9f)
            i++
        }
        // Goal progress, in the wave's own terms.
        val txt = when (wave.goal) {
            GOAL_RESCUE -> "FREED ${game.freedCount()}/${game.neededFree()}"
            GOAL_DEFEND -> "SHIELDS ${game.shieldsLeft()}/4"
            GOAL_BONUS -> "BONUS  ${game.aliveCount()} LEFT"
            GOAL_DIVERS -> "LEFT ${game.aliveCount()}"
            else -> "LEFT ${game.aliveCount()}"
        }
        VectorFont.draw(batch, txt, -0.130f, -0.108f, 0.0020f, 0.6f, 0.9f, 0.7f, 0.6f)

        if (stateT < 2.2f) {
            val a = if (stateT > 1.7f) (2.2f - stateT) / 0.5f else 1f
            VectorFont.draw(batch, wave.blurb, 0f, 0.050f, 0.0026f, 1f, 0.8f, 0.4f, a * 0.9f, true)
        }
    }

    private fun drawMenu() {
        VectorFont.draw(batch, "TAPSHOT", 0f, 0.062f, 0.0068f, 0.4f, 1f, 1f, 1f, true)

        val sel0 = row == ROW_RELAX
        VectorFont.draw(batch, "SPEED", -0.106f, 0.014f, 0.0028f, 0.55f, 0.9f, 1f, if (sel0) 1f else 0.45f)
        VectorFont.draw(batch, if (settings.relaxed) "RELAXED" else "NORMAL", 0.006f, 0.014f, 0.0028f,
            1f, 0.85f, 0.35f, if (sel0) 1f else 0.45f)
        if (sel0) caret(-0.128f, 0.014f, 0.0028f)

        val sel1 = row == ROW_START
        val pulse = if (sel1) 0.55f + 0.45f * sin(stateT * 3f) else 0.4f
        VectorFont.draw(batch, "PLAY", 0f, -0.022f, 0.0042f, 1f, 1f, 0.6f, pulse, true)
        if (sel1) caret(-0.078f, -0.022f, 0.0042f)

        VectorFont.draw(batch, "DRAG   MOVE CANNON", -0.132f, -0.058f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "TAP    FIRE", -0.132f, -0.076f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "SWIPE  MENU ONLY", -0.132f, -0.094f, 0.0019f, 0.5f, 0.9f, 1f, 0.5f)
        VectorFont.draw(batch, "BACK   QUIT RUN", -0.132f, -0.112f, 0.0019f, 0.5f, 0.9f, 1f, 0.38f)
    }

    private fun caret(u: Float, v: Float, size: Float) {
        val h = size * 1.3f
        val cy = v + size * 1.4f
        batch.line(u, cy + h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
        batch.line(u, cy - h, u + h * 1.2f, cy, 0.0010f, 1f, 1f, 0.6f, 0.9f)
    }

    private fun drawWaveEnd() {
        VectorFont.draw(batch, "WAVE ${waveNo} CLEAR", 0f, 0.030f, 0.0040f, 0.4f, 1f, 0.7f, 1f, true)
        VectorFont.draw(batch, "${game.score}", 0f, 0.004f, 0.0030f, 1f, 1f, 1f, 0.9f, true)
        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR NEXT", 0f, -0.026f, 0.0028f, 1f, 1f, 0.6f, pulse, true)
        if (stateT < 0.8f && parts.live < 200) parts.burst(0f, 0.030f, 5, 0.06f, 0.4f, 1f, 0.8f, 1.1f)
    }

    private fun drawOver() {
        VectorFont.draw(batch, "GAME OVER", 0f, 0.034f, 0.0046f, 1f, 0.35f, 0.4f, 1f, true)
        VectorFont.draw(batch, "SCORE ${game.score}", 0f, 0.006f, 0.0030f, 1f, 1f, 1f, 0.9f, true)
        VectorFont.draw(batch, "WAVE $waveNo", 0f, -0.016f, 0.0024f, 0.7f, 0.9f, 1f, 0.8f, true)
        val pulse = 0.5f + 0.5f * sin(stateT * 3f)
        VectorFont.draw(batch, "TAP FOR MENU", 0f, -0.044f, 0.0028f, 1f, 1f, 0.6f, pulse, true)
    }
}
