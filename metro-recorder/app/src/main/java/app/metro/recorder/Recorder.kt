package app.metro.recorder

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * Пишет всё, что видит телефон, в CSV-файлы папки [dir].
 * Время во всех файлах — t_ms: миллисекунды с момента включения телефона
 * (одна шкала для датчиков, Wi-Fi, вышек и GPS).
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class Recorder(private val ctx: Context, val dir: File, private val scanIntervalSec: Int) {
    private val thread = HandlerThread("recorder").apply { start() }
    private val h = Handler(thread.looper)
    private val onThread = Executor { h.post(it) }

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val wm = ctx.getSystemService(WifiManager::class.java)
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val bm = ctx.getSystemService(BatteryManager::class.java)
    private val pm = ctx.getSystemService(PowerManager::class.java)

    val startElapsed = SystemClock.elapsedRealtime()
    private val startWall = System.currentTimeMillis()

    private val sensors = Csv(File(dir, "sensors.csv"), "t_ms,sensor,v0,v1,v2,v3")
    private val wifi = Csv(File(dir, "wifi.csv"), "scan_no,t_seen_ms,t_got_ms,ssid,bssid,rssi,freq_mhz,width,standard")
    private val scans = Csv(File(dir, "wifi_scans.csv"), "scan_no,t_ms,kind,ok,results,new_results")
    private val cells = Csv(File(dir, "cells.csv"), "t_got_ms,t_cell_ms,registered,connection,type,mcc,mnc,area,cell_id,pci,arfcn,dbm,quality")
    private val locs = Csv(File(dir, "locations.csv"), "t_ms,wall_ms,provider,lat,lon,alt,acc_m,vacc_m,speed,bearing,mock")
    private val gnss = Csv(File(dir, "gnss.csv"), "t_ms,sats,used,cn0_used_mean,cn0_max,gps,glonass,galileo,beidou")
    private val gnssRaw = Csv(File(dir, "gnss_raw.csv"), "t_ms,measurements,cn0_mean,agc_db_mean")
    private val battery = Csv(File(dir, "battery.csv"), "t_ms,percent,charge_uah,current_ua,temp_c10,plugged")
    private val events = Csv(File(dir, "events.csv"), "t_ms,wall_ms,event,detail")
    private val marks = Csv(File(dir, "marks.csv"), "t_ms,wall_ms,kind,line,station,next,towards,note")
    private val all = listOf(sensors, wifi, scans, cells, locs, gnss, gnssRaw, battery, events, marks)

    @Volatile private var stopped = false
    private var wakeLock: PowerManager.WakeLock? = null

    private fun now() = SystemClock.elapsedRealtime()

    // ---------- запуск и остановка ----------

    fun start() {
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "metro:recorder").apply {
            setReferenceCounted(false)
            acquire(8 * 3600 * 1000L)
        }
        h.post {
            writeMeta(null)
            event("start", "scan_interval_s=$scanIntervalSec")
            event("state", stateSummary())
            startSensors()
            startWifi()
            startLocation()
            h.post(cellTask)
            h.post(batteryTask)
            h.postDelayed(flushTask, 2000)
        }
    }

    /** Останавливает запись; [onDone] вызывается в главном потоке, когда файлы закрыты. */
    fun stop(onDone: () -> Unit) {
        val main = Handler(Looper.getMainLooper())
        h.post {
            if (stopped) return@post
            stopped = true
            h.removeCallbacksAndMessages(null)
            safe { sm.unregisterListener(sensorListener) }
            safe { ctx.unregisterReceiver(receiver) }
            safe { lm.removeUpdates(locListener) }
            safe { lm.unregisterGnssStatusCallback(gnssStatusCb) }
            safe { lm.unregisterGnssMeasurementsCallback(gnssMeasCb) }
            events.line("${now()},${System.currentTimeMillis()},stop,")
            all.forEach { it.close() }
            safe { writeMeta(now()) }
            safe { wakeLock?.release() }
            main.post(onDone)
            thread.quitSafely()
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    fun event(kind: String, detail: String) {
        val t = now()
        val w = System.currentTimeMillis()
        h.post { if (!stopped) events.line("$t,$w,$kind,${esc(detail)}") }
    }

    fun mark(kind: String, line: String, station: String, next: String, towards: String, note: String) {
        val t = now()
        val w = System.currentTimeMillis()
        h.post {
            if (stopped) return@post
            marks.line("$t,$w,$kind,${esc(line)},${esc(station)},${esc(next)},${esc(towards)},${esc(note)}")
            marks.flush()
            Live.marks++
        }
    }

    private val flushTask = object : Runnable {
        override fun run() {
            if (stopped) return
            all.forEach { it.flush() }
            Live.bytes = all.sumOf { it.file.length() }
            h.postDelayed(this, 2000)
        }
    }

    // ---------- датчики ----------

    /** Код датчика в sensors.csv → (тип, период опроса в мкс). */
    private val sensorSpec = listOf(
        Triple("A", Sensor.TYPE_ACCELEROMETER, 20_000),       // ускорение, м/с², с гравитацией
        Triple("G", Sensor.TYPE_GYROSCOPE, 20_000),           // повороты, рад/с
        Triple("M", Sensor.TYPE_MAGNETIC_FIELD, 20_000),      // магнитное поле, мкТл (моторы поезда)
        Triple("R", Sensor.TYPE_GAME_ROTATION_VECTOR, 20_000), // положение телефона без компаса
        Triple("P", Sensor.TYPE_PRESSURE, 100_000),           // давление, гПа
        Triple("L", Sensor.TYPE_LIGHT, 200_000),              // освещённость, лк
        Triple("S", Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL), // шаг
    )
    private val codeByType = sensorSpec.associate { it.second to it.first }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (stopped) return
            val code = codeByType[e.sensor.type] ?: return
            val v = e.values
            val sb = StringBuilder(72)
            sb.append(e.timestamp / 1_000_000).append(',').append(code)
            for (i in 0 until 4) {
                sb.append(',')
                if (i < v.size) sb.append(v[i])
            }
            sensors.line(sb.toString())
            when (code) {
                "P" -> {
                    Live.pressure = v[0]
                    if (Live.pressureStart.isNaN()) Live.pressureStart = v[0]
                }
                "L" -> Live.light = v[0]
            }
        }

        override fun onAccuracyChanged(s: Sensor, accuracy: Int) {
            event("sensor_accuracy", "${codeByType[s.type]}=$accuracy")
        }
    }

    private fun startSensors() {
        for ((code, type, period) in sensorSpec) {
            val s = sm.getDefaultSensor(type)
            if (s == null) {
                event("sensor_missing", code)
                continue
            }
            val ok = try {
                sm.registerListener(sensorListener, s, period, 0, h)
            } catch (e: Exception) {
                false
            }
            event("sensor", "$code ok=$ok ${s.name} (${s.vendor})")
        }
    }

    // ---------- Wi-Fi и состояние телефона ----------

    private var scanNo = 0
    private val lastSeen = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (stopped) return
            when (i.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION ->
                    onScanResults(i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
                WifiManager.WIFI_STATE_CHANGED_ACTION ->
                    event("wifi_state", i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1).toString())
                Intent.ACTION_SCREEN_ON -> event("screen", "on")
                Intent.ACTION_SCREEN_OFF -> event("screen", "off")
                Intent.ACTION_USER_PRESENT -> event("screen", "unlocked")
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> event("airplane", i.getBooleanExtra("state", false).toString())
            }
        }
    }

    private fun startWifi() {
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, null, h, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, f, null, h)
        }
        h.post(scanTask)
    }

    private val scanTask = object : Runnable {
        override fun run() {
            if (stopped) return
            scanNo++
            val ok = try {
                wm.startScan()
            } catch (e: Exception) {
                false
            }
            scans.line("$scanNo,${now()},request,${if (ok) 1 else 0},,")
            if (ok) Live.wifiScansOk++ else Live.wifiScansRefused++
            h.removeCallbacks(this)
            // В режиме «как можно чаще» следующий поиск запускается сразу после результатов,
            // а эта задержка — запасной вариант, если результаты не пришли.
            val delay = when {
                scanIntervalSec > 0 -> scanIntervalSec * 1000L
                ok -> 10_000L
                else -> 3_000L
            }
            h.postDelayed(this, delay)
        }
    }

    private fun ssidOf(r: ScanResult): String = r.SSID ?: ""

    private fun onScanResults(updated: Boolean) {
        val t = now()
        val list = try {
            wm.scanResults
        } catch (e: SecurityException) {
            event("error", "scanResults: ${e.message}")
            return
        }
        var fresh = 0
        for (r in list) {
            val bssid = r.BSSID ?: continue
            val seen = r.timestamp / 1000 // мкс с включения → мс
            val prev = lastSeen[bssid]
            if (prev != null && seen <= prev) continue
            lastSeen[bssid] = seen
            fresh++
            val std = if (Build.VERSION.SDK_INT >= 30) r.wifiStandard else 0
            wifi.line("$scanNo,$seen,$t,${esc(ssidOf(r))},$bssid,${r.level},${r.frequency},${r.channelWidth},$std")
        }
        scans.line("$scanNo,$t,result,${if (updated) 1 else 0},${list.size},$fresh")

        val recent = list.filter { t - it.timestamp / 1000 < 20_000 }
        Live.netsTotal = recent.size
        Live.aiThinker = recent.count { ssidOf(it).startsWith("AI-THINKER", ignoreCase = true) }
        Live.mtFree = recent.count { ssidOf(it).contains("MT_FREE", ignoreCase = true) }
        Live.wifiTop = recent.sortedWith(Comparator { a, b -> b.level.compareTo(a.level) }).take(12)
            .map { WifiItem(ssidOf(it), it.BSSID ?: "", it.level, it.frequency) }
        if (updated) Live.lastScanAt = t

        if (scanIntervalSec <= 0 && updated) {
            h.removeCallbacks(scanTask)
            h.postDelayed(scanTask, 300)
        }
    }

    // ---------- вышки сотовой связи ----------

    private val cellTask = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                tm.requestCellInfoUpdate(onThread, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        if (!stopped) logCells(cellInfo)
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        event("cell_error", "$errorCode ${detail?.message ?: ""}")
                    }
                })
            } catch (e: Exception) {
                event("error", "cells: ${e.message}")
            }
            h.postDelayed(this, 3000)
        }
    }

    private class CellRow(
        val type: String, val mcc: String, val mnc: String, val area: String, val cid: String,
        val pci: String, val arfcn: String, val dbm: String, val q: String,
    )

    private fun v(x: Int) = if (x == Int.MAX_VALUE || x == Int.MIN_VALUE) "" else x.toString()
    private fun vl(x: Long) = if (x == Long.MAX_VALUE) "" else x.toString()

    private fun describe(c: CellInfo): CellRow = when (c) {
        is CellInfoLte -> {
            val i = c.cellIdentity
            val s = c.cellSignalStrength
            CellRow("LTE", i.mccString ?: "", i.mncString ?: "", v(i.tac), v(i.ci), v(i.pci), v(i.earfcn), v(s.rsrp), v(s.rsrq))
        }
        is CellInfoNr -> {
            val i = c.cellIdentity as CellIdentityNr
            val s = c.cellSignalStrength as CellSignalStrengthNr
            CellRow("NR", i.mccString ?: "", i.mncString ?: "", v(i.tac), vl(i.nci), v(i.pci), v(i.nrarfcn), v(s.ssRsrp), v(s.ssRsrq))
        }
        is CellInfoGsm -> {
            val i = c.cellIdentity
            val s = c.cellSignalStrength
            CellRow("GSM", i.mccString ?: "", i.mncString ?: "", v(i.lac), v(i.cid), v(i.bsic), v(i.arfcn), v(s.dbm), "")
        }
        is CellInfoWcdma -> {
            val i = c.cellIdentity
            val s = c.cellSignalStrength
            CellRow("WCDMA", i.mccString ?: "", i.mncString ?: "", v(i.lac), v(i.cid), v(i.psc), v(i.uarfcn), v(s.dbm), "")
        }
        else -> CellRow(c.javaClass.simpleName, "", "", "", "", "", "", "", "")
    }

    private fun logCells(list: List<CellInfo>) {
        val t = now()
        var serving: String? = null
        for (c in list) {
            val ts = if (Build.VERSION.SDK_INT >= 30) c.timestampMillis else c.timeStamp / 1_000_000
            val r = describe(c)
            val reg = if (c.isRegistered) 1 else 0
            cells.line(
                "$t,$ts,$reg,${c.cellConnectionStatus},${r.type},${r.mcc},${r.mnc},${r.area}," +
                    "${r.cid},${r.pci},${r.arfcn},${r.dbm},${r.q}"
            )
            if (reg == 1 && serving == null) {
                serving = "${r.type} ${r.mcc}-${r.mnc} · зона ${r.area} · вышка ${r.cid} · ${r.dbm} дБм"
            }
        }
        Live.cellCount = list.size
        Live.cellText = serving ?: if (list.isEmpty()) "вышек не видно" else "нет основной вышки"
    }

    // ---------- GPS и геопозиция по сети ----------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (!stopped) logLocation(l)
        }

        override fun onProviderEnabled(provider: String) {
            event("provider_on", provider)
        }

        override fun onProviderDisabled(provider: String) {
            event("provider_off", provider)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }
    }

    private fun logLocation(l: Location) {
        val t = l.elapsedRealtimeNanos / 1_000_000
        val mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider
        locs.line(
            "$t,${l.time},${l.provider}," +
                "${l.latitude},${l.longitude}," +
                "${if (l.hasAltitude()) l.altitude else ""}," +
                "${if (l.hasAccuracy()) l.accuracy else ""}," +
                "${if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else ""}," +
                "${if (l.hasSpeed()) l.speed else ""}," +
                "${if (l.hasBearing()) l.bearing else ""}," +
                (if (mock) 1 else 0)
        )
        if (l.provider == LocationManager.GPS_PROVIDER) {
            Live.lastGpsAt = now()
            Live.lastGpsAcc = l.accuracy
        } else {
            Live.lastNetAt = now()
        }
    }

    private val gnssStatusCb = object : GnssStatus.Callback() {
        override fun onStarted() = event("gnss", "started")
        override fun onStopped() = event("gnss", "stopped")
        override fun onFirstFix(ttffMillis: Int) = event("gnss", "first_fix ttff_ms=$ttffMillis")

        override fun onSatelliteStatusChanged(s: GnssStatus) {
            if (stopped) return
            val n = s.satelliteCount
            var used = 0
            var sumUsed = 0f
            var maxCn0 = 0f
            val byType = IntArray(8)
            for (i in 0 until n) {
                val cn0 = s.getCn0DbHz(i)
                if (cn0 > maxCn0) maxCn0 = cn0
                if (s.usedInFix(i)) {
                    used++
                    sumUsed += cn0
                }
                val ct = s.getConstellationType(i)
                if (ct in byType.indices) byType[ct]++
            }
            val mean = if (used > 0) sumUsed / used else 0f
            gnss.line(
                "${now()},$n,$used,${f1(mean)},${f1(maxCn0)}," +
                    "${byType[GnssStatus.CONSTELLATION_GPS]},${byType[GnssStatus.CONSTELLATION_GLONASS]}," +
                    "${byType[GnssStatus.CONSTELLATION_GALILEO]},${byType[GnssStatus.CONSTELLATION_BEIDOU]}"
            )
            Live.satsUsed = used
            Live.satsTotal = n
        }
    }

    /** Сила сигнала и усиление приёмника: помогает заметить глушение и подмену GPS. */
    private val gnssMeasCb = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(e: GnssMeasurementsEvent) {
            if (stopped) return
            val ms = e.measurements
            var sumCn0 = 0.0
            var agcSum = 0.0
            var agcN = 0
            for (m in ms) {
                sumCn0 += m.cn0DbHz
                if (Build.VERSION.SDK_INT < 33 && m.hasAutomaticGainControlLevelDb()) {
                    agcSum += m.automaticGainControlLevelDb
                    agcN++
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                for (a in e.gnssAutomaticGainControls) {
                    agcSum += a.levelDb
                    agcN++
                }
            }
            val cn0 = if (ms.isNotEmpty()) sumCn0 / ms.size else 0.0
            val agc = if (agcN > 0) f2(agcSum / agcN) else ""
            gnssRaw.line("${now()},${ms.size},${f1(cn0)},$agc")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(status: Int) {
            event("gnss_raw_status", status.toString())
        }
    }

    private fun startLocation() {
        val providers = lm.allProviders
        val wanted = mutableListOf(
            LocationManager.GPS_PROVIDER to 1000L,
            LocationManager.NETWORK_PROVIDER to 2000L,
        )
        if (Build.VERSION.SDK_INT >= 31) wanted += LocationManager.FUSED_PROVIDER to 2000L
        for ((p, every) in wanted) {
            if (p !in providers) {
                event("provider_missing", p)
                continue
            }
            try {
                lm.requestLocationUpdates(p, every, 0f, locListener, thread.looper)
                event("provider", p)
            } catch (e: Exception) {
                event("error", "location $p: ${e.message}")
            }
        }
        try {
            lm.registerGnssStatusCallback(gnssStatusCb, h)
        } catch (e: Exception) {
            event("error", "gnss status: ${e.message}")
        }
        try {
            lm.registerGnssMeasurementsCallback(gnssMeasCb, h)
        } catch (e: Exception) {
            event("error", "gnss raw: ${e.message}")
        }
    }

    // ---------- батарея ----------

    private val batteryTask = object : Runnable {
        override fun run() {
            if (stopped) return
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = st?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            battery.line("${now()},$pct,$uah,$ua,$temp,$plugged")
            if (Live.batteryStart < 0) Live.batteryStart = pct
            Live.batteryNow = pct
            h.postDelayed(this, 30_000)
        }
    }

    // ---------- описание записи ----------

    private fun stateSummary(): String {
        val wifiOn = wm.isWifiEnabled
        val scanAlways = wm.isScanAlwaysAvailable
        val locOn = lm.isLocationEnabled
        return "wifi_on=$wifiOn scan_always=$scanAlways location_on=$locOn throttle=${throttleSetting(ctx)}"
    }

    private fun writeMeta(stopElapsed: Long?) {
        val o = JSONObject()
        o.put("app", "MetroRecorder")
        o.put("appVersion", appVersion(ctx))
        o.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        o.put("deviceCode", Build.DEVICE)
        o.put("android", Build.VERSION.RELEASE)
        o.put("sdk", Build.VERSION.SDK_INT)
        o.put("timezone", TimeZone.getDefault().id)
        o.put("startWallMs", startWall)
        o.put("startElapsedMs", startElapsed)
        o.put(
            "timeNote",
            "t_ms во всех файлах — миллисекунды с включения телефона. " +
                "Время по часам = startWallMs + (t_ms - startElapsedMs)."
        )
        o.put("scanIntervalSec", scanIntervalSec)
        o.put("wifiThrottleSetting", throttleSetting(ctx))
        o.put("hasBarometer", sm.getDefaultSensor(Sensor.TYPE_PRESSURE) != null)
        val arr = JSONArray()
        for (s in sm.getSensorList(Sensor.TYPE_ALL)) {
            arr.put(
                JSONObject()
                    .put("type", s.type)
                    .put("stringType", s.stringType)
                    .put("name", s.name)
                    .put("vendor", s.vendor)
                    .put("resolution", s.resolution.toDouble())
                    .put("maxRange", s.maximumRange.toDouble())
                    .put("minDelayUs", s.minDelay)
                    .put("powerMa", s.power.toDouble())
            )
        }
        o.put("sensors", arr)
        if (stopElapsed != null) {
            o.put("stopElapsedMs", stopElapsed)
            o.put("durationSec", (stopElapsed - startElapsed) / 1000)
            o.put("wifiScansOk", Live.wifiScansOk)
            o.put("wifiScansRefused", Live.wifiScansRefused)
            o.put("marks", Live.marks)
        }
        File(dir, "meta.json").writeText(o.toString(2))
    }

    companion object {
        fun f1(x: Float) = String.format(Locale.US, "%.1f", x)
        fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
        fun f2(x: Double) = String.format(Locale.US, "%.2f", x)

        /** "0"/"1" — выключено/включено ограничение поиска Wi-Fi, "?" — система не даёт прочитать. */
        fun throttleSetting(ctx: Context): String = try {
            Settings.Global.getInt(ctx.contentResolver, "wifi_scan_throttle_enabled").toString()
        } catch (e: Exception) {
            "?"
        }

        fun appVersion(ctx: Context): String = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
