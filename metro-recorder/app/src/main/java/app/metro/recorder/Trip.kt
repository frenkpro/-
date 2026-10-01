package app.metro.recorder

import android.content.Context

/**
 * Где сейчас едем: ветка, станция, направление. Нужна только для отметок,
 * чтобы кнопка «Прибыли» сама подставляла следующую станцию.
 * Все обращения — из главного потока.
 */
object Trip {
    var lineId = 0
        private set
    var stationIdx = -1
        private set
    var forward = true
        private set

    /** 0 — искать Wi-Fi как можно чаще, иначе пауза в секундах. */
    var scanIntervalSec = 0
        private set

    var lastMark = ""

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        lineId = p.getInt("line", 0)
        stationIdx = p.getInt("station", -1)
        forward = p.getBoolean("forward", true)
        scanIntervalSec = p.getInt("scan", 0)
        loaded = true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trip", Context.MODE_PRIVATE)

    private fun save(ctx: Context) {
        prefs(ctx).edit()
            .putInt("line", lineId)
            .putInt("station", stationIdx)
            .putBoolean("forward", forward)
            .putInt("scan", scanIntervalSec)
            .apply()
    }

    val line: Line? get() = Metro.line(lineId)

    val station: String get() = line?.stations?.getOrNull(stationIdx) ?: ""

    /** Конечная, в сторону которой едем. */
    val towards: String
        get() {
            val l = line ?: return ""
            return if (forward) l.stations.last() else l.stations.first()
        }

    private fun nextIdx(): Int {
        val l = line ?: return -1
        if (stationIdx < 0) return -1
        val n = stationIdx + if (forward) 1 else -1
        return if (n in l.stations.indices) n else -1
    }

    val next: String get() = line?.stations?.getOrNull(nextIdx()) ?: ""

    fun setLine(ctx: Context, id: Int) {
        lineId = id
        stationIdx = -1
        save(ctx)
    }

    fun setStation(ctx: Context, idx: Int) {
        stationIdx = idx
        save(ctx)
    }

    fun toggleDirection(ctx: Context) {
        forward = !forward
        save(ctx)
    }

    /** Переходим на следующую станцию (по кнопке «Прибыли»). */
    fun advance(ctx: Context) {
        val n = nextIdx()
        if (n >= 0) {
            stationIdx = n
            save(ctx)
        }
    }

    fun setScanInterval(ctx: Context, sec: Int) {
        scanIntervalSec = sec
        save(ctx)
    }
}
