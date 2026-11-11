package org.openstrap.app.data

/** Pure helpers over a day's stored heart rate readings (time, bpm); kept free of Android types so they're unit-testable. */
object HeartReadings {
    /** The readings of [series] taken from [from] to [to]. */
    fun within(series: List<Pair<Long, Int>>, from: Long, to: Long): List<Pair<Long, Int>> =
        series.filter { it.first in from..to }

    /** The lowest reading of [series], the earliest when several share it, with its time; null without readings. */
    fun lowest(series: List<Pair<Long, Int>>): Pair<Long, Int>? = series.minByOrNull { it.second }

    /** The mean of [series]' readings, to the nearest bpm; null without readings. */
    fun mean(series: List<Pair<Long, Int>>): Int? =
        series.takeIf { it.isNotEmpty() }?.let { Math.round(it.map { r -> r.second }.average()).toInt() }

    /**
     * The day's lowest and highest heart rate from its stored readings ([series], not their 5-minute averages). The
     * high is the strap's own daily maximum ([strapMax]) when it has one that is higher. Null without readings.
     */
    fun heartRange(series: List<Pair<Long, Int>>, strapMax: Int?): Pair<Int, Int>? {
        if (series.isEmpty()) return null
        val high = series.maxOf { it.second }
        return series.minOf { it.second } to maxOf(high, strapMax?.takeIf { it in HeartRate.VALID } ?: high)
    }
}
