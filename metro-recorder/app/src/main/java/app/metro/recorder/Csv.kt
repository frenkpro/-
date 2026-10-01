package app.metro.recorder

import java.io.File
import java.io.IOException

/** CSV-файл с буфером. Пишется только из потока записи. */
class Csv(val file: File, header: String) {
    private val w = file.bufferedWriter(Charsets.UTF_8, 64 * 1024)
    private var closed = false

    init {
        line(header)
    }

    fun line(s: String) {
        if (closed) return
        try {
            w.write(s)
            w.write("\n")
        } catch (e: IOException) {
            closed = true
        }
    }

    fun flush() {
        if (closed) return
        try {
            w.flush()
        } catch (_: IOException) {
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            w.close()
        } catch (_: IOException) {
        }
    }
}

/** Экранирование значения для CSV (названия сетей бывают с запятыми и кавычками). */
fun esc(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    val needs = s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needs) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
