package nl.tinnisoft.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class NoiseColor { WHITE, PINK, BROWN, BLUE, VIOLET, GREY }

@Serializable
enum class NatureSound { RAIN, WAVES, BROOK, WIND, CRICKETS, FAN }

@Serializable
enum class SourceKind {
    /** Plain colored noise. */
    NOISE,

    /** Colored noise with a band around the tinnitus frequency removed. */
    NOTCHED,

    /** Narrow-band noise centered on the tinnitus frequency. */
    NARROWBAND,

    /** Synthesized or recorded nature sound. */
    NATURE,
}

@Serializable
data class LayerConfig(
    val kind: SourceKind = SourceKind.NOISE,
    val color: NoiseColor = NoiseColor.PINK,
    val nature: NatureSound = NatureSound.RAIN,
    /** 0..1, relative layer level inside the mix. */
    val volume: Float = 0.8f,
    /** Center of the notch / narrow band in Hz. */
    val centerHz: Float = 4000f,
    /** Notch width in octaves (default one octave). */
    val notchWidthOct: Float = 1f,
    /** Narrow-band width in octaves. */
    val bandWidthOct: Float = 0.5f,
) {
    /** Layers with the same identity can be updated in place instead of cross-faded. */
    val identity: String
        get() = when (kind) {
            SourceKind.NATURE -> "N:$nature"
            SourceKind.NARROWBAND -> "B"
            else -> "$kind:$color"
        }
}

@Serializable
data class MixConfig(
    val layers: List<LayerConfig> = listOf(LayerConfig()),
    /** Gains in dB for [EQ_BANDS_HZ]. */
    val eqGainsDb: List<Float> = List(EQ_BANDS_HZ.size) { 0f },
    val modulationEnabled: Boolean = false,
    /** 0..1 depth of the slow amplitude modulation. */
    val modulationDepth: Float = 0.3f,
    val modulationPeriodSec: Float = 12f,
    /** -1 = left only … 0 = center … 1 = right only. */
    val balance: Float = 0f,
) {
    fun toJson(): String = json.encodeToString(this)

    fun normalized(): MixConfig = copy(
        layers = layers.take(MAX_LAYERS),
        eqGainsDb = List(EQ_BANDS_HZ.size) { eqGainsDb.getOrElse(it) { 0f }.coerceIn(-12f, 12f) },
        modulationDepth = modulationDepth.coerceIn(0f, 1f),
        modulationPeriodSec = modulationPeriodSec.coerceIn(2f, 60f),
        balance = balance.coerceIn(-1f, 1f),
    )

    companion object {
        const val MAX_LAYERS = 3
        val EQ_BANDS_HZ = floatArrayOf(63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f)
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(s: String?): MixConfig? =
            s?.let { runCatching { json.decodeFromString<MixConfig>(it).normalized() }.getOrNull() }
    }
}

/** Range of the tinnitus/notch frequency. */
object FrequencyRange {
    const val MIN_HZ = 250f
    const val MAX_HZ = 16000f
}
