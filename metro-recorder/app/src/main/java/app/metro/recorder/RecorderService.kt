package app.metro.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Работает, пока идёт запись: держит постоянное уведомление (с ним Android не
 * усыпляет приложение при выключенном экране) и принимает отметки из уведомления.
 */
class RecorderService : Service() {

    companion object {
        const val ACTION_START = "app.metro.recorder.START"
        const val ACTION_STOP = "app.metro.recorder.STOP"
        const val ACTION_MARK = "app.metro.recorder.MARK"
        const val EXTRA_KIND = "kind"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        @Volatile
        var recorder: Recorder? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecorderService::class.java).setAction(ACTION_STOP))
        }

        fun fmtDuration(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) {
                String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            } else {
                String.format("%d:%02d", s / 60, s % 60)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    private val notifTask = object : Runnable {
        override fun run() {
            if (recorder == null) return
            nm.notify(NOTIF_ID, buildNotification())
            main.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_STOP -> finish()
            ACTION_MARK -> {
                val kind = intent.getStringExtra(EXTRA_KIND)
                if (kind != null) Marks.add(this, kind)
                if (recorder != null) nm.notify(NOTIF_ID, buildNotification()) else stopSelf()
            }
            else -> if (recorder == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin() {
        createChannel()
        try {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) {
            Live.error = "Android не дал запустить запись: ${e.message}"
            stopSelf()
            return
        }
        if (recorder != null) return
        Trip.load(this)
        Live.reset()
        val r = Recorder(applicationContext, Sessions.newSessionDir(this), Trip.scanIntervalSec)
        recorder = r
        r.start()
        nm.notify(NOTIF_ID, buildNotification())
        main.postDelayed(notifTask, 5000)
    }

    private fun finish() {
        val r = recorder
        if (r == null) {
            stopSelf()
            return
        }
        recorder = null
        main.removeCallbacks(notifTask)
        Live.finishing = true
        nm.notify(NOTIF_ID, buildNotification())
        r.stop {
            Sessions.archive(applicationContext, r.dir) { savedTo ->
                Live.lastSaved = savedTo
                Live.finishing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // Если систему всё же закрыла службу — хотя бы закрываем файлы.
        recorder?.let { r ->
            recorder = null
            r.stop {}
        }
        main.removeCallbacks(notifTask)
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Запись поездки", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    private fun smallIcon(): Int {
        val id = resources.getIdentifier("ic_stat_rec", "drawable", packageName)
        return if (id != 0) id else android.R.drawable.ic_menu_mylocation
    }

    private fun buildNotification(): Notification {
        Trip.load(this)
        val r = recorder
        val title = when {
            Live.finishing -> "Сохраняю запись…"
            r == null -> "Метро · запись"
            else -> "Запись · ${fmtDuration(SystemClock.elapsedRealtime() - r.startElapsed)}"
        }
        val where = when {
            Trip.station.isEmpty() -> "Станция не выбрана"
            Trip.next.isNotEmpty() -> "${Trip.station} → ${Trip.next}"
            else -> Trip.station
        }
        val stats = "Wi-Fi: ${Live.wifiScansOk} · отметок: ${Live.marks}"
        val body = if (Trip.lastMark.isNotEmpty()) "$where\n$stats\n${Trip.lastMark}" else "$where\n$stats"

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(smallIcon())
            .setContentTitle(title)
            .setContentText("$where · $stats")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (r != null && !Live.finishing) {
            b.addAction(markAction("depart", "Тронулись", 1))
            b.addAction(markAction("brake", "Тормозим", 2))
            b.addAction(markAction("arrive", "Прибыли", 3))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    private fun markAction(kind: String, label: String, code: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, code,
            Intent(this, RecorderService::class.java).setAction(ACTION_MARK).putExtra(EXTRA_KIND, kind),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(this, smallIcon()), label, pi).build()
    }
}
