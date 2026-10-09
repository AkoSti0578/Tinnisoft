package nl.tinnisoft.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Turns a recording into a seamless loop: the last [crossfadeFrames] of the recording are
 * blended (equal-power) into its first frames, and the tail is dropped. Playing the result
 * in a circle has no seam: at the wrap point the signal continues exactly as the original
 * recording continued.
 */
object SeamlessLoop {
    fun build(input: FloatArray, crossfadeFrames: Int): FloatArray {
        val xf = crossfadeFrames.coerceIn(0, input.size / 3)
        if (xf == 0) return input.copyOf()
        val n = input.size - xf
        val out = input.copyOf(n)
        for (i in 0 until xf) {
            val t = (i + 0.5) / xf
            val fadeIn = sin(t * PI / 2).toFloat()
            val fadeOut = cos(t * PI / 2).toFloat()
            out[i] = input[i] * fadeIn + input[n + i] * fadeOut
        }
        return out
    }
}

/** Plays two seamless loop buffers in a circle. Different start offsets keep L/R decorrelated. */
class LoopPlayer(private val left: FloatArray, private val right: FloatArray, private val gain: Float) : StereoSource {
    private var posL = 0
    private var posR = right.size / 2

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            left[i] = this.left[posL] * gain
            right[i] = this.right[posR] * gain
            if (++posL >= this.left.size) posL = 0
            if (++posR >= this.right.size) posR = 0
        }
    }
}
