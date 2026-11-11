package org.openstrap.app.presentation

import androidx.annotation.StringRes
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.i18n.Strings

/**
 * How stressed a stress reading is, in Zepp's bands: relaxed 0 to 39, normal 40 to 59, medium 60 to 79, high 80 to 100
 * (the same bands [org.openstrap.app.data.Recovery.STRESS_FROM] documents). A reading is judged by the level of the
 * number shown, not against your usual.
 */
enum class StressLevel(@StringRes private val id: Int) {
    Relaxed(R.string.glow_stress_relaxed),
    Normal(R.string.glow_stress_normal),
    Medium(R.string.glow_stress_medium),
    High(R.string.glow_stress_high),
    ;

    /** The level's name: "Relaxed", "Normal", "Medium" or "High". */
    fun word(strings: Strings = Strings.app): String = strings.get(id)

    companion object {
        private const val NORMAL_FROM = 40
        private const val MEDIUM_FROM = 60
        private const val HIGH_FROM = 80

        /** The level of a stress [value], read as the whole number it is shown as, so the word agrees with the digits. */
        fun of(value: Float): StressLevel {
            val shown = value.roundToInt()
            return when {
                shown >= HIGH_FROM -> High
                shown >= MEDIUM_FROM -> Medium
                shown >= NORMAL_FROM -> Normal
                else -> Relaxed
            }
        }
    }
}
