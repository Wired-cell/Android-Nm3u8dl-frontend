package app.nm3u8.frontend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import java.io.File
import java.util.concurrent.Executors

class DownloadService : Service() {
    companion object {
        const val ACTION_START = "app.nm3u8.frontend.DOWNLOAD_START"
        const val ACTION_STOP = "app.nm3u8.frontend.DOWNLOAD_STOP"
        const val ACTION_LOG = "app.nm3u8.frontend.DOWNLOAD_LOG"
        const val ACTION_FINISHED = "app.nm3u8.frontend.DOWNLOAD_FINISHED"
        const val ACTION_STATE = "app.nm3u8.frontend.DOWNLOAD_STATE"
        const val EXTRA_ARGS = "args"
        const val EXTRA_WORK_DIR = "work_dir"
        const val EXTRA_HOME = "home"
        const val EXTRA_TMP = "tmp"
        const val EXTRA_NATIVE_DIR = "native_dir"
        const val EXTRA_DOWNLOAD_DIR = "download_dir"
        const val EXTRA_LINE = "line"
        const val EXTRA_PROGRESS = "progress"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2401
    }

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running: Process? = null
    @Volatile private var stopping = false
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "N_m3u8DL-RE download progress"
                setShowBadge(false)
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                running?.destroy()
                updateNotification("Stopping download…", null, true)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("Preparing download…", null, true))
                sendState()
                val args = intent.getStringArrayListExtra(EXTRA_ARGS)?.toList()
                if (args.isNullOrEmpty()) {
                    finish("Download failed: command was empty.", false)
                    return START_NOT_STICKY
                }
                val work = File(intent.getStringExtra(EXTRA_WORK_DIR).orEmpty())
                val home = intent.getStringExtra(EXTRA_HOME).orEmpty()
                val tmp = intent.getStringExtra(EXTRA_TMP).orEmpty()
                val native = intent.getStringExtra(EXTRA_NATIVE_DIR).orEmpty()
                val output = File(intent.getStringExtra(EXTRA_DOWNLOAD_DIR).orEmpty())
                worker.execute { runDownload(args, work, home, tmp, native, output) }
            }
        }
        return START_NOT_STICKY
    }

    private fun runDownload(args: List<String>, work: File, home: String, tmp: String, native: String, output: File) {
        try {
            val builder = ProcessBuilder(args).directory(work).redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", home)
                put("TMPDIR", tmp)
                put("PATH", "$native:${work.absolutePath}:${get("PATH").orEmpty()}")
                put("LD_LIBRARY_PATH", "$native:${work.absolutePath}:${get("LD_LIBRARY_PATH").orEmpty()}")
            }
            val child = builder.start()
            running = child
            var pending = StringBuilder()
            child.inputStream.buffered().use { stream ->
                while (true) {
                    val ch = stream.read()
                    if (ch < 0) break
                    if (ch == 10 || ch == 13) {
                        if (pending.isNotEmpty()) {
                            publishLine(pending.toString())
                            pending = StringBuilder()
                        }
                    } else if (pending.length < 1200) pending.append(ch.toChar())
                }
            }
            if (pending.isNotEmpty()) publishLine(pending.toString())
            val code = child.waitFor()
            running = null
            if (stopping) finish("Download stopped.", false)
            else if (code == 0) {
                val mediaFiles = findMediaFiles(output)
                if (mediaFiles.isEmpty()) {
                    val entries = output.walkTopDown().filter { it.isFile }
                        .take(12).joinToString { "${it.name} (${it.length()} bytes)" }
                        .ifEmpty { "directory is empty" }
                    finish(
                        "Download FAILED: engine exited with code 0 but produced no media files. Output: ${output.absolutePath}. Found: $entries. The manifest was parsed, but segment download did not complete.",
                        false
                    )
                } else {
                    val count = publishDownloads(mediaFiles, output)
                    finish("Download complete. Published $count media file(s) to Downloads/N_m3u8DL-RE.", true)
                }
            } else finish("Downloader exited with code $code. No files were published. Output directory: ${output.absolutePath}", false)
        } catch (e: Exception) {
            finish("Download failed: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun publishLine(line: String) {
        val safe = if (line.contains("--key") || line.contains("KID:", true)) "[Sensitive argument output hidden]" else line
        sendBroadcast(Intent(ACTION_LOG).setPackage(packageName).putExtra(EXTRA_LINE, safe))
        val clean = safe.replace(Regex("\\u001B\\[[;\\d?]*[ -/]*[@-~]"), "")
        val percent = Regex("""(?<!\d)(\d{1,3})%""").find(clean)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
        if (percent != null) sendBroadcast(Intent(ACTION_LOG).setPackage(packageName)
            .putExtra(EXTRA_LINE, "DOWNLOAD_PROGRESS:$percent").putExtra(EXTRA_PROGRESS, percent))
        val notificationText = when {
            safe.contains("Loading URL:", true) -> "Loading manifest…"
            safe.contains("Signature=", true) || safe.contains("Policy=", true) || safe.contains("Key-Pair-Id=", true) -> "Processing request…"
            percent != null -> "Downloading segments: $percent%"
            else -> clean.takeLast(110)
        }
        updateNotification(notificationText, percent, true)
    }

    private fun buildNotification(status: String, percent: Int?, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("N_m3u8DL-RE")
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (ongoing) builder.addAction(android.R.drawable.ic_media_pause, "Stop", stop)
        if (percent == null && ongoing) builder.setProgress(0, 0, true)
        else if (percent != null) builder.setProgress(100, percent, false)
        return builder.build()
    }

    private fun updateNotification(status: String, percent: Int?, ongoing: Boolean) {
        manager.notify(NOTIFICATION_ID, buildNotification(status, percent, ongoing))
    }

    private fun sendState() {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
    }

    private fun finish(message: String, success: Boolean) {
        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra(EXTRA_LINE, message))
        updateNotification(message, if (success) 100 else null, false)
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        stopSelf()
    }

    private fun findMediaFiles(root: File): List<File> {
        if (!root.exists()) return emptyList()
        val mediaExtensions = setOf("mp4", "m4v", "mkv", "ts", "m4s", "mpg", "mpeg", "m4a", "mp3", "aac", "ac3", "eac3", "webm", "vtt", "srt")
        return root.walkTopDown().filter {
            it.isFile && it.length() > 0L && it.extension.lowercase() in mediaExtensions
        }.toList()
    }

    private fun publishDownloads(files: List<File>, root: File): Int {
        var published = 0
        for (file in files) {
            val relativeParent = file.parentFile?.relativeTo(root)?.path?.takeIf { it.isNotEmpty() }
            val folder = if (relativeParent == null) "Download/N_m3u8DL-RE/" else "Download/N_m3u8DL-RE/$relativeParent/"
            val mime = when (file.extension.lowercase()) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "vtt" -> "text/vtt"
                "srt" -> "application/x-subrip"
                else -> "application/octet-stream"
            }
            val values = ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads entry for ${file.name}")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IllegalStateException("Could not write ${file.name}")
                contentResolver.update(uri, ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                published++
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }
        return published
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running?.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }
}
