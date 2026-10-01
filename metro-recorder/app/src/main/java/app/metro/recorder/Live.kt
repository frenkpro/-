package app.metro.recorder

class WifiItem(val ssid: String, val bssid: String, val rssi: Int, val freq: Int)

/** Сводка «что видит телефон прямо сейчас» для экрана. Пишет только поток записи. */
object Live {
    @Volatile var wifiScansOk = 0
    @Volatile var wifiScansRefused = 0
    @Volatile var lastScanAt = 0L
    @Volatile var netsTotal = 0
    @Volatile var aiThinker = 0
    @Volatile var mtFree = 0
    @Volatile var wifiTop: List<WifiItem> = emptyList()

    @Volatile var cellText = ""
    @Volatile var cellCount = 0

    @Volatile var satsUsed = 0
    @Volatile var satsTotal = 0
    @Volatile var lastGpsAt = 0L
    @Volatile var lastGpsAcc = 0f
    @Volatile var lastNetAt = 0L

    @Volatile var pressure = Float.NaN
    @Volatile var pressureStart = Float.NaN
    @Volatile var light = Float.NaN

    @Volatile var batteryStart = -1
    @Volatile var batteryNow = -1

    @Volatile var bytes = 0L
    @Volatile var marks = 0

    /** Пока архивируется только что остановленная запись. */
    @Volatile var finishing = false
    @Volatile var lastSaved = ""
    @Volatile var error = ""

    fun reset() {
        wifiScansOk = 0; wifiScansRefused = 0; lastScanAt = 0L
        netsTotal = 0; aiThinker = 0; mtFree = 0; wifiTop = emptyList()
        cellText = ""; cellCount = 0
        satsUsed = 0; satsTotal = 0; lastGpsAt = 0L; lastGpsAcc = 0f; lastNetAt = 0L
        pressure = Float.NaN; pressureStart = Float.NaN; light = Float.NaN
        batteryStart = -1; batteryNow = -1
        bytes = 0L; marks = 0
        error = ""
    }
}
