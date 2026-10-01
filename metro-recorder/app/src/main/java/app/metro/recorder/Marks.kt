package app.metro.recorder

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ручные отметки во время поездки — «правильные ответы» для будущего анализа. */
object Marks {
    val labels = mapOf(
        "entrance" to "Вход в метро",
        "platform" to "На платформе",
        "board" to "Сел в вагон",
        "depart" to "Тронулись",
        "brake" to "Тормозим",
        "arrive" to "Прибыли",
        "doors_left" to "Двери слева",
        "doors_right" to "Двери справа",
        "tunnel_stop" to "Стоп в тоннеле",
        "alight" to "Вышел из вагона",
        "surface" to "На улице",
        "note" to "Заметка",
        "station_set" to "Выбрана станция",
        "direction" to "Сменил направление",
    )

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Возвращает false, если запись не идёт. Вызывать из главного потока. */
    fun add(ctx: Context, kind: String, note: String = ""): Boolean {
        val rec = RecorderService.recorder ?: return false
        Trip.load(ctx)
        if (kind == "arrive") Trip.advance(ctx)
        val line = Trip.line?.name ?: ""
        val station = Trip.station
        val next = Trip.next
        rec.mark(kind, line, station, next, Trip.towards, note)

        val where = when (kind) {
            "depart", "brake" -> if (next.isNotEmpty()) "→ $next" else ""
            else -> station
        }
        Trip.lastMark = listOf(labels[kind] ?: kind, where, clock.format(Date()))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        return true
    }
}
