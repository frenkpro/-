package app.metro.recorder

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Папки с записями, архивы и отправка. */
object Sessions {
    const val AUTHORITY = "app.metro.recorder.files"
    private const val DOWNLOADS_SUBDIR = "MetroRecorder"
    private val main = Handler(Looper.getMainLooper())

    class Info(val dir: File, val sizeBytes: Long, val durationSec: Long, val marks: Int)

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").apply { mkdirs() }

    fun exportsDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }

    fun newSessionDir(ctx: Context): File {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(root(ctx), name).apply { mkdirs() }
    }

    fun list(ctx: Context): List<Info> {
        val active = RecorderService.recorder?.dir
        return (root(ctx).listFiles() ?: emptyArray())
            .filter { it.isDirectory && it != active }
            .sortedWith(Comparator { a, b -> b.name.compareTo(a.name) })
            .map { d ->
                val meta = try {
                    JSONObject(File(d, "meta.json").readText())
                } catch (e: Exception) {
                    null
                }
                Info(
                    d,
                    (d.listFiles() ?: emptyArray()).sumOf { it.length() },
                    meta?.optLong("durationSec", -1L) ?: -1L,
                    meta?.optInt("marks", 0) ?: 0,
                )
            }
    }

    private fun zipName(dir: File) = "metro_${dir.name}.zip"

    private fun zip(ctx: Context, dir: File): File {
        val out = File(exportsDir(ctx), zipName(dir))
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (f in (dir.listFiles() ?: emptyArray()).sortedWith(Comparator { a, b -> a.name.compareTo(b.name) })) {
                z.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    /** Копия архива в «Загрузки/MetroRecorder» — её видно в «Моих файлах» и она переживёт удаление приложения. */
    private fun copyToDownloads(ctx: Context, zip: File): Boolean {
        return try {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOADS_SUBDIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
            cr.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
            cv.clear()
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, cv, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** После остановки: архив + копия в «Загрузки». [done] получает текст для экрана. */
    fun archive(ctx: Context, dir: File, done: (String) -> Unit) {
        Thread {
            val msg = try {
                val z = zip(ctx, dir)
                if (copyToDownloads(ctx, z)) {
                    "Сохранено: Загрузки/$DOWNLOADS_SUBDIR/${z.name}"
                } else {
                    "Запись сохранена в приложении (в «Загрузки» скопировать не удалось)"
                }
            } catch (e: Exception) {
                "Ошибка архивации: ${e.message}"
            }
            main.post { done(msg) }
        }.start()
    }

    fun share(activity: Activity, dir: File) {
        Thread {
            val z = try {
                val existing = File(exportsDir(activity), zipName(dir))
                if (existing.exists()) existing else zip(activity, dir)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (z == null) {
                    Live.error = "Не удалось собрать архив"
                    return@post
                }
                val uri = Uri.parse("content://$AUTHORITY/${z.name}")
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, z.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(z.name, uri)
                activity.startActivity(Intent.createChooser(send, "Отправить запись"))
            }
        }.start()
    }

    fun delete(ctx: Context, dir: File) {
        dir.deleteRecursively()
        File(exportsDir(ctx), zipName(dir)).delete()
    }
}
