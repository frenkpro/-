package app.metro.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private class Palette(val dark: Boolean) {
        val bg = if (dark) 0xFF111113.toInt() else 0xFFEEEEF0.toInt()
        val card = if (dark) 0xFF1C1C1F.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF2F2F4.toInt() else 0xFF1C1C1E.toInt()
        val sub = if (dark) 0xFF9A9AA0.toInt() else 0xFF6E6E73.toInt()
        val btn = if (dark) 0xFF2A2A2E.toInt() else 0xFFE6E6EA.toInt()
        val warnBg = if (dark) 0xFF3A2E14.toInt() else 0xFFFFF1CC.toInt()
        val warnText = if (dark) 0xFFFFD98A.toInt() else 0xFF6B4A00.toInt()
        val red = 0xFFD6083B.toInt()
        val thinker = 0xFFEA7125.toInt()
        val mtFree = 0xFF0078C9.toInt()
    }

    private lateinit var c: Palette
    private val ui = Handler(Looper.getMainLooper())
    private var pendingStart = false
    private var wasActive = false

    // Обновляемые элементы экрана
    private lateinit var status: TextView
    private lateinit var startBtn: TextView
    private lateinit var warnBox: TextView
    private lateinit var savedText: TextView
    private val lineChips = mutableMapOf<Int, TextView>()
    private lateinit var stationBtn: TextView
    private lateinit var dirBtn: TextView
    private lateinit var arriveBtn: TextView
    private val markButtons = mutableListOf<View>()
    private lateinit var lastMark: TextView
    private lateinit var liveWifi: TextView
    private lateinit var liveNets: TextView
    private lateinit var liveCell: TextView
    private lateinit var liveGps: TextView
    private lateinit var livePressure: TextView
    private lateinit var liveBattery: TextView
    private lateinit var prepPerms: TextView
    private lateinit var prepBattery: TextView
    private lateinit var prepThrottle: TextView
    private val intervalChips = mutableMapOf<Int, TextView>()
    private lateinit var sessionsBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Trip.load(this)
        c = Palette((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        actionBar?.hide()
        setContentView(buildScreen())
        styleSystemBars()
        rebuildSessions()
    }

    override fun onResume() {
        super.onResume()
        rebuildSessions()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    // ---------- построение экрана ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = c.bg
        window.navigationBarColor = c.bg
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (c.dark) 0 else mask, mask)
        } else if (!c.dark) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun rounded(color: Int, radius: Int = 14, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (strokeColor != null) setStroke(dp(3), strokeColor)
    }

    private fun pressable(color: Int, radius: Int = 14, strokeColor: Int? = null) = RippleDrawable(
        ColorStateList.valueOf(if (c.dark) 0x33FFFFFF else 0x22000000),
        rounded(color, radius, strokeColor),
        null,
    )

    private fun label(s: String, size: Float = 15f, color: Int = c.text, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun button(text: String, bg: Int = c.btn, fg: Int = c.text, size: Float = 15f, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = size
            setTextColor(fg)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = pressable(bg)
            setPadding(dp(8), dp(13), dp(8), dp(13))
            isClickable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    private fun row(vararg views: View, gap: Int = 8): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(gap)
            })
        }
    }

    private fun LinearLayout.add(v: View, top: Int = 8) {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
        })
    }

    private fun card(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.card, 18)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        addView(label(title.uppercase(Locale.getDefault()), 12f, c.sub, bold = true).apply { letterSpacing = 0.08f })
    }

    private fun markButton(text: String, kind: String, size: Float = 15f) =
        button(text, size = size) { mark(kind) }.also { markButtons += it }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }

        root.add(label("Метро · запись", 24f, bold = true), 4)
        status = label("", 14f, c.sub)
        root.add(status, 2)

        startBtn = button("Начать запись", size = 17f) { onStartStop() }
        root.add(startBtn, 14)

        warnBox = label("", 14f, c.warnText).apply {
            background = rounded(c.warnBg, 14)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
        }
        root.add(warnBox, 10)

        savedText = label("", 13f, c.sub).apply { visibility = View.GONE }
        root.add(savedText, 8)

        root.add(buildTripCard(), 12)
        root.add(buildLiveCard(), 12)
        root.add(buildPrepCard(), 12)

        val sessions = card("Записи")
        sessionsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sessions.add(sessionsBox, 4)
        root.add(sessions, 12)

        return ScrollView(this).apply {
            setBackgroundColor(c.bg)
            isFillViewport = true
            addView(root)
        }
    }

    private fun buildTripCard(): View {
        val card = card("Поездка")

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Metro.lines.forEachIndexed { i, line ->
            val chip = TextView(this).apply {
                text = line.id.toString()
                gravity = Gravity.CENTER
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    Trip.setLine(this@MainActivity, line.id)
                    refresh()
                    chooseStation()
                }
            }
            lineChips[line.id] = chip
            chips.addView(chip, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        card.add(chips, 10)

        stationBtn = button("") { chooseStation() }
        dirBtn = button("") {
            if (Trip.line == null) return@button
            Trip.toggleDirection(this)
            Marks.add(this, "direction")
            refresh()
        }
        card.add(stationBtn, 10)
        card.add(dirBtn, 8)

        card.add(row(markButton("Тронулись", "depart"), markButton("Тормозим", "brake")), 14)
        arriveBtn = markButton("Прибыли", "arrive", 16f)
        card.add(arriveBtn, 8)
        card.add(row(markButton("← Двери слева", "doors_left"), markButton("Двери справа →", "doors_right")), 8)
        card.add(row(markButton("Стоп в тоннеле", "tunnel_stop"), button("Заметка") { askNote() }.also { markButtons += it }), 8)
        card.add(label("Двери — по ходу движения поезда.", 12f, c.sub), 6)

        card.add(row(
            markButton("Вход в метро", "entrance", 13f),
            markButton("На платформе", "platform", 13f),
            markButton("Сел в вагон", "board", 13f),
            gap = 6,
        ), 12)
        card.add(row(
            markButton("Вышел из вагона", "alight", 13f),
            markButton("На улице", "surface", 13f),
            gap = 6,
        ), 6)

        lastMark = label("", 13f, c.sub)
        card.add(lastMark, 10)
        return card
    }

    private fun buildLiveCard(): View {
        val card = card("Что видит телефон")
        liveWifi = label("", 14f)
        liveNets = label("", 12f, c.sub).apply { typeface = Typeface.MONOSPACE }
        liveCell = label("", 14f)
        liveGps = label("", 14f)
        livePressure = label("", 14f)
        liveBattery = label("", 14f)
        card.add(liveWifi, 8)
        card.add(liveNets, 6)
        card.add(liveCell, 10)
        card.add(liveGps, 6)
        card.add(livePressure, 6)
        card.add(liveBattery, 6)
        return card
    }

    private fun buildPrepCard(): View {
        val card = card("Подготовка телефона")

        prepPerms = label("", 14f)
        card.add(prepPerms, 8)
        card.add(button("Выдать разрешения") { requestPerms() }, 6)

        prepBattery = label("", 14f)
        card.add(prepBattery, 14)
        card.add(button("Снять ограничения батареи") { openBatterySettings() }, 6)

        prepThrottle = label("", 14f)
        card.add(prepThrottle, 14)
        card.add(label(
            "Android по умолчанию разрешает искать Wi-Fi только 4 раза за 2 минуты. Для записи это " +
                "ограничение нужно выключить:\n" +
                "1. Настройки → Сведения о телефоне → Сведения о ПО → 7 раз нажать «Номер сборки».\n" +
                "2. Настройки → Параметры разработчика → пункт про ограничение (регулирование) " +
                "поиска Wi-Fi, по-английски «Wi-Fi scan throttling» → выключить.",
            13f, c.sub,
        ), 6)
        card.add(button("Открыть параметры разработчика") { openDeveloperSettings() }, 8)

        card.add(label("Как часто искать Wi-Fi", 14f), 14)
        val opts = listOf(0 to "Чаще всего", 5 to "5 с", 10 to "10 с", 30 to "30 с")
        val chips = opts.map { (sec, text) ->
            button(text, size = 13f) {
                Trip.setScanInterval(this, sec)
                if (RecorderService.recorder != null) toast("Применится к следующей записи")
                refresh()
            }.also { intervalChips[sec] = it }
        }
        card.add(row(*chips.toTypedArray(), gap = 6), 6)
        return card
    }

    // ---------- обновление ----------

    private fun refresh() {
        val rec = RecorderService.recorder
        val active = rec != null

        if (wasActive && !active) rebuildSessions()
        wasActive = active

        status.text = when {
            Live.finishing -> "Сохраняю запись…"
            active -> "Идёт запись · ${mb(Live.bytes)}"
            else -> "Запись не идёт"
        }
        if (active) {
            startBtn.text = "Остановить · ${RecorderService.fmtDuration(SystemClock.elapsedRealtime() - rec!!.startElapsed)}"
            startBtn.background = pressable(c.red)
            startBtn.setTextColor(Color.WHITE)
        } else {
            startBtn.text = if (Live.finishing) "Сохраняю…" else "Начать запись"
            startBtn.background = pressable(c.text)
            startBtn.setTextColor(c.bg)
        }
        startBtn.isEnabled = !Live.finishing

        val warns = warnings(active)
        warnBox.text = warns.joinToString("\n\n")
        warnBox.visibility = if (warns.isEmpty()) View.GONE else View.VISIBLE
        savedText.text = Live.lastSaved
        savedText.visibility = if (Live.lastSaved.isEmpty() || active) View.GONE else View.VISIBLE

        refreshTrip(active)
        refreshLive(active)
        refreshPrep()
    }

    private fun refreshTrip(active: Boolean) {
        val line = Trip.line
        for ((id, chip) in lineChips) {
            val l = Metro.line(id) ?: continue
            val selected = id == Trip.lineId
            chip.background = rounded(l.color, 12, if (selected) c.text else null)
            chip.alpha = if (line == null || selected) 1f else 0.45f
        }
        stationBtn.text = when {
            line == null -> "Выберите ветку"
            Trip.station.isEmpty() -> "Станция: выбрать ▾"
            else -> "Станция: ${Trip.station} ▾"
        }
        dirBtn.text = if (line == null) "Направление" else "Направление: → ${Trip.towards}"
        val next = Trip.next
        arriveBtn.text = if (next.isNotEmpty()) "Прибыли: $next" else "Прибыли"
        if (line != null && active) {
            arriveBtn.background = pressable(line.color)
            arriveBtn.setTextColor(Color.WHITE)
        } else {
            arriveBtn.background = pressable(c.btn)
            arriveBtn.setTextColor(c.text)
        }
        for (b in markButtons) {
            b.isEnabled = active
            b.alpha = if (active) 1f else 0.4f
        }
        lastMark.text = if (Trip.lastMark.isNotEmpty()) "Последняя отметка: ${Trip.lastMark}" else
            if (active) "Отмечайте события кнопками — они станут «правильными ответами» для анализа." else
                "Кнопки отметок работают во время записи. Три главные есть и в уведомлении."
    }

    private fun refreshLive(active: Boolean) {
        if (!active) {
            liveWifi.text = "Данные появятся после начала записи."
            liveNets.text = ""
            liveNets.visibility = View.GONE
            liveCell.text = ""
            liveGps.text = ""
            livePressure.text = ""
            liveBattery.text = ""
            return
        }
        val now = SystemClock.elapsedRealtime()
        val age = if (Live.lastScanAt > 0) "${(now - Live.lastScanAt) / 1000} с назад" else "ещё не было"
        liveWifi.text = "Wi-Fi: поисков ${Live.wifiScansOk}, отказов ${Live.wifiScansRefused}, последний $age\n" +
            "Сетей рядом: ${Live.netsTotal} · AI-THINKER: ${Live.aiThinker} · MT_FREE: ${Live.mtFree}"

        val top = Live.wifiTop
        if (top.isEmpty()) {
            liveNets.visibility = View.GONE
        } else {
            val sb = SpannableStringBuilder()
            for (n in top) {
                val name = n.ssid.ifEmpty { "(скрытая)" }
                val band = when {
                    n.freq >= 5925 -> "6"
                    n.freq >= 4900 -> "5"
                    else -> "2.4"
                }
                val line = String.format(Locale.US, "%4d %-3s %-20.20s %s\n", n.rssi, band, name, n.bssid.takeLast(8))
                val start = sb.length
                sb.append(line)
                val color = when {
                    name.startsWith("AI-THINKER", ignoreCase = true) -> c.thinker
                    name.contains("MT_FREE", ignoreCase = true) -> c.mtFree
                    else -> null
                }
                if (color != null) sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            liveNets.text = sb
            liveNets.visibility = View.VISIBLE
        }

        liveCell.text = "Вышки: ${Live.cellCount} видно\n${Live.cellText.ifEmpty { "ждём данные…" }}"

        val gps = if (Live.lastGpsAt > 0) {
            val sec = (now - Live.lastGpsAt) / 1000
            if (sec < 5) "есть, точность ${Live.lastGpsAcc.roundToInt()} м" else "пропал ${RecorderService.fmtDuration(now - Live.lastGpsAt)} назад"
        } else "нет"
        liveGps.text = "GPS: $gps · спутников ${Live.satsUsed} из ${Live.satsTotal}"

        livePressure.text = if (Live.pressure.isNaN()) {
            "Барометр: данных нет (возможно, его нет в телефоне)"
        } else {
            val change = Live.pressure - Live.pressureStart
            // ≈ 0,12 гПа на метр высоты у уровня моря; спуск вниз = давление растёт
            String.format(Locale.US, "Давление: %.2f гПа · с начала %+.2f (≈ %+.0f м)", Live.pressure, change, -change / 0.12f)
        }

        val light = if (Live.light.isNaN()) "" else " · свет ${Live.light.roundToInt()} лк"
        liveBattery.text = if (Live.batteryNow >= 0) {
            "Батарея: ${Live.batteryNow}% (с начала ${Live.batteryNow - Live.batteryStart}%)$light"
        } else "Батарея: —$light"
    }

    private fun refreshPrep() {
        val missing = missingPerms()
        prepPerms.text = if (missing.isEmpty()) "Разрешения: все выданы ✓" else "Разрешения: не выданы — ${missing.size}"
        val pm = getSystemService(PowerManager::class.java)
        prepBattery.text = if (pm.isIgnoringBatteryOptimizations(packageName)) {
            "Батарея: ограничений нет ✓"
        } else {
            "Батарея: Android может усыплять запись"
        }
        val setting = Recorder.throttleSetting(this)
        prepThrottle.text = when {
            setting == "0" -> "Ограничение поиска Wi-Fi: выключено ✓"
            setting == "1" -> "Ограничение поиска Wi-Fi: включено"
            Live.wifiScansRefused > 0 -> "Ограничение поиска Wi-Fi: включено (Android отказывает в поиске)"
            Live.wifiScansOk > 8 -> "Ограничение поиска Wi-Fi: похоже, выключено ✓"
            else -> "Ограничение поиска Wi-Fi: проверится во время записи"
        }
        for ((sec, chip) in intervalChips) {
            val sel = sec == Trip.scanIntervalSec
            chip.background = pressable(if (sel) c.text else c.btn)
            chip.setTextColor(if (sel) c.bg else c.text)
        }
    }

    @Suppress("DEPRECATION")
    private fun warnings(active: Boolean): List<String> {
        val w = mutableListOf<String>()
        if (Live.error.isNotEmpty()) w += Live.error
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            w += "Нет разрешения на точную геопозицию. Без него Android не показывает приложению ни Wi-Fi сети, ни вышки."
        }
        val lm = getSystemService(LocationManager::class.java)
        if (!lm.isLocationEnabled) w += "Геолокация выключена — включите её в шторке."
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        if (!wm.isWifiEnabled && !wm.isScanAlwaysAvailable) {
            w += "Wi-Fi выключен — включите его (подключаться к сети не нужно)."
        }
        if (active && Live.wifiScansRefused > 0 && Live.wifiScansRefused >= Live.wifiScansOk / 4) {
            w += "Android ограничивает поиск Wi-Fi (отказов: ${Live.wifiScansRefused}). Выключите ограничение — см. «Подготовка телефона»."
        }
        return w
    }

    // ---------- сессии ----------

    private fun rebuildSessions() {
        if (!::sessionsBox.isInitialized) return
        sessionsBox.removeAllViews()
        val list = Sessions.list(this)
        if (list.isEmpty()) {
            sessionsBox.add(label("Пока пусто. После остановки запись появится здесь и в папке «Загрузки/MetroRecorder».", 14f, c.sub), 6)
            return
        }
        for (s in list) {
            val name = prettyName(s.dir.name)
            val dur = if (s.durationSec >= 0) RecorderService.fmtDuration(s.durationSec * 1000) else "—"
            val info = label("$name\n$dur · ${mb(s.sizeBytes)} · отметок ${s.marks}", 14f)
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.add(info, 0)
            box.add(row(
                button("Отправить", size = 13f) { Sessions.share(this, s.dir) },
                button("Удалить", size = 13f, fg = c.red) { confirmDelete(s) },
                gap = 6,
            ), 6)
            sessionsBox.add(box, 12)
        }
    }

    private fun prettyName(dirName: String): String {
        // 2026-10-02_08-15-30 → 2026-10-02 08:15
        val parts = dirName.split('_')
        if (parts.size != 2) return dirName
        val t = parts[1].split('-')
        return if (t.size >= 2) "${parts[0]} ${t[0]}:${t[1]}" else dirName
    }

    private fun confirmDelete(s: Sessions.Info) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage("${prettyName(s.dir.name)} — удалится из приложения. Копия в «Загрузках» останется.")
            .setPositiveButton("Удалить") { _, _ ->
                Sessions.delete(this, s.dir)
                rebuildSessions()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ---------- действия ----------

    private fun onStartStop() {
        if (Live.finishing) return
        if (RecorderService.recorder != null) {
            AlertDialog.Builder(this)
                .setTitle("Остановить запись?")
                .setMessage("Файлы сохранятся и попадут в «Загрузки/MetroRecorder».")
                .setPositiveButton("Остановить") { _, _ ->
                    RecorderService.stop(this)
                    ui.postDelayed({ refresh() }, 300)
                }
                .setNegativeButton("Продолжить запись", null)
                .show()
            return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true
            requestPerms()
            return
        }
        startRecording()
    }

    private fun startRecording() {
        Live.lastSaved = ""
        Live.error = ""
        Trip.lastMark = ""
        RecorderService.start(this)
        ui.postDelayed({ refresh() }, 300)
    }

    private fun mark(kind: String) {
        if (!Marks.add(this, kind)) {
            toast("Сначала начните запись")
            return
        }
        refresh()
    }

    private fun askNote() {
        val input = EditText(this).apply {
            hint = "Что произошло?"
            setSingleLine(false)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle("Заметка")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Marks.add(this, "note", text)
                    refresh()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseStation() {
        val line = Trip.line ?: run {
            toast("Сначала выберите ветку")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${line.id}. ${line.name}")
            .setItems(line.stations.toTypedArray()) { _, i ->
                Trip.setStation(this, i)
                Marks.add(this, "station_set")
                refresh()
            }
            .show()
    }

    private fun missingPerms(): List<String> {
        val all = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            all += Manifest.permission.POST_NOTIFICATIONS
            all += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        return all.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPerms() {
        val missing = missingPerms()
        if (missing.isEmpty()) {
            toast("Все разрешения уже выданы")
            return
        }
        requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (pendingStart) {
            pendingStart = false
            if (fine) startRecording() else toast("Без точной геопозиции запись не имеет смысла")
        }
        refresh()
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Ограничений уже нет")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Не нашёл настройку батареи")
            }
        }
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            toast("Сначала включите параметры разработчика (шаг 1)")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f МБ", bytes / 1_048_576.0)
}
