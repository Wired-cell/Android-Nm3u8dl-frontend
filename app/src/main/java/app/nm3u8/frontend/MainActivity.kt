package app.nm3u8.frontend

import android.app.Activity
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false
    private lateinit var urlInput: EditText
    private lateinit var argumentsInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var output: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var nativeDir: File
    private lateinit var downloadDir: File

    private val bg = 0xFF282A36.toInt()
    private val panel = 0xFF44475A.toInt()
    private val fg = 0xFFF8F8F2.toInt()
    private val muted = 0xFFBD93F9.toInt()
    private val green = 0xFF50FA7B.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.setSystemUiVisibility(0)
        nativeDir = File(filesDir, "native")
        downloadDir = File(getExternalFilesDir(null), "download-staging/N_m3u8DL-RE")
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        executor.execute {
            try {
                installNativePayload()
                val binary = File(applicationInfo.nativeLibraryDir, "libN_m3u8DL-RE.so")
                if (!binary.exists() || !binary.canExecute()) {
                    throw IllegalStateException("ARM64 engine was not extracted as an executable native library: ${binary.absolutePath}")
                }
                val ssl = File(nativeDir, "libssl.so")
                val crypto = File(nativeDir, "libcrypto.so")
                val sslProbe = File(applicationInfo.nativeLibraryDir, "libopenssl-probe.so")
                if (!ssl.isFile || !crypto.isFile || !sslProbe.canExecute()) {
                    throw IllegalStateException("Bundled OpenSSL or its loader test is missing from ${nativeDir.absolutePath}.")
                }
                val probeBuilder = ProcessBuilder(sslProbe.absolutePath)
                    .directory(nativeDir).redirectErrorStream(true)
                probeBuilder.environment()["LD_LIBRARY_PATH"] =
                    "${nativeDir.absolutePath}:${applicationInfo.nativeLibraryDir}"
                val probe = probeBuilder.start()
                val probeOutput = probe.inputStream.bufferedReader().use { it.readText() }.trim()
                val probeCode = probe.waitFor()
                if (probeCode != 0) {
                    throw IllegalStateException("OpenSSL loader test failed (code $probeCode): $probeOutput")
                }
                runOnUiThread { appendLog(probeOutput) }
                // Android app data is mounted noexec on many devices. Execute these tools
                // from nativeLibraryDir, where APK native libraries are extracted executable.
                val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg-launcher.so")
                if (!ffmpeg.isFile || !ffmpeg.canExecute()) {
                    throw IllegalStateException("ARM64 FFmpeg launcher is missing or not executable: ${ffmpeg.absolutePath}")
                }
                val ffmpegBuilder = ProcessBuilder(ffmpeg.absolutePath, "-version")
                    .directory(nativeDir).redirectErrorStream(true)
                // The launcher deliberately resets LD_LIBRARY_PATH to nativeLibraryDir
                // before execing FFmpeg, preventing our private OpenSSL 3 from
                // shadowing Android platform libraries such as libcrypto/libsqlite.
                val ffmpegCheck = ffmpegBuilder.start()
                val ffmpegVersion = ffmpegCheck.inputStream.bufferedReader().readLine().orEmpty()
                val ffmpegCode = ffmpegCheck.waitFor()
                if (ffmpegCode != 0 || !ffmpegVersion.contains("ffmpeg", ignoreCase = true)) {
                    throw IllegalStateException("FFmpeg launch test failed (code $ffmpegCode): $ffmpegVersion")
                }
                val mp4decrypt = File(applicationInfo.nativeLibraryDir, "libmp4decrypt.so")
                if (!mp4decrypt.isFile || !mp4decrypt.canExecute()) {
                    throw IllegalStateException("ARM64 mp4decrypt executable is missing or not executable: ${mp4decrypt.absolutePath}")
                }
                // The bundled v0.6.0-beta engine ignores --decryption-binary-path:
                // its MP4DECRYPT branch calls FindExecutable("mp4decrypt"), which
                // searches the process working directory before PATH. Put a symlink
                // with that exact name in nativeDir; its target remains the executable
                // extracted from the APK into nativeLibraryDir (the noexec-safe location).
                val mp4decryptLookup = File(nativeDir, "mp4decrypt")
                Files.deleteIfExists(mp4decryptLookup.toPath())
                Files.createSymbolicLink(mp4decryptLookup.toPath(), mp4decrypt.toPath())
                if (!mp4decryptLookup.isFile || !mp4decryptLookup.canExecute() ||
                    mp4decryptLookup.canonicalFile != mp4decrypt.canonicalFile) {
                    throw IllegalStateException("mp4decrypt PATH shim is invalid: ${mp4decryptLookup.absolutePath}")
                }
                // Test the exact path the engine's FindExecutable() will discover,
                // not merely the underlying APK-extracted binary.
                val decryptBuilder = ProcessBuilder(mp4decryptLookup.absolutePath, "--help")
                    .directory(nativeDir).redirectErrorStream(true)
                val decryptCheck = decryptBuilder.start()
                val decryptOutput = decryptCheck.inputStream.bufferedReader().readText()
                val decryptCode = decryptCheck.waitFor()
                if (decryptOutput.isBlank()) {
                    throw IllegalStateException("mp4decrypt PATH shim launch test failed (code $decryptCode).")
                }
                runOnUiThread {
                    appendLog("mp4decrypt PATH lookup verified: ${mp4decryptLookup.absolutePath} -> ${mp4decryptLookup.canonicalPath} (exit $decryptCode)")
                }
                val check = ProcessBuilder(binary.absolutePath, "--version")
                    .directory(nativeDir).redirectErrorStream(true).start()
                val version = check.inputStream.bufferedReader().use { it.readText() }.trim()
                val code = check.waitFor()
                runOnUiThread {
                    appendLog(if (code == 0) "ARM64 engine ready: $version" else "Engine returned exit code $code. Check bundled runtime files.")
                    runButton.isEnabled = code == 0
                }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Engine setup failed: ${e.message}") }
            }
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_LOG -> {
                    val percent = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, -1)
                    if (percent >= 0) updateDownloadProgress(percent)
                    intent.getStringExtra(DownloadService.EXTRA_LINE)?.takeUnless { it.startsWith("DOWNLOAD_PROGRESS:") }?.let { appendLog(it) }
                }
                DownloadService.ACTION_FINISHED -> {
                    appendLog(intent.getStringExtra(DownloadService.EXTRA_LINE).orEmpty())
                    runButton.isEnabled = true
                    stopButton.isEnabled = false
                }
                DownloadService.ACTION_STATE -> { runButton.isEnabled = false; stopButton.isEnabled = true }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(DownloadService.ACTION_LOG); addAction(DownloadService.ACTION_FINISHED); addAction(DownloadService.ACTION_STATE)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(downloadReceiver, filter)
        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) { unregisterReceiver(downloadReceiver); receiverRegistered = false }
        super.onStop()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(18))
            setBackgroundColor(bg)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "N_m3u8DL-RE"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
        })
        content.addView(TextView(this).apply {
            text = "ANDROID • ARM64 • DRACULA"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(20))
        })

        urlInput = field(content, "MPD / playlist URL", "https://example.com/manifest.mpd")
        content.addView(TextView(this).apply {
            text = "VIDEO QUALITY"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            setPadding(0, dp(12), 0, dp(6))
        })
        qualitySpinner = Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto (240p)", "Best available", "720p • 1280×720", "480p • 854×480", "360p • 640×360", "240p • 426×240")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(0)
        }
        content.addView(qualitySpinner, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
        argumentsInput = field(content, "Arguments", "--key \"KID:KEY\"", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 4
            maxLines = 8
        }
        qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                syncQualityArguments(position)
            }
        }
        syncQualityArguments(0)
        content.addView(TextView(this).apply {
            text = "The selected video quality is mirrored into Arguments automatically. Audio is always set to best. Add your decryption key as --key \"KID:KEY\". HTTP headers are only needed when the server requires them."
            textSize = 12f
            setTextColor(0xFFBFBFBF.toInt())
            setPadding(0, dp(4), 0, dp(16))
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        runButton = Button(this).apply {
            text = "Start download"
            isEnabled = false
            setOnClickListener { startDownload() }
        }
        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener { startService(Intent(this@MainActivity, DownloadService::class.java).setAction(DownloadService.ACTION_STOP)) }
        }
        buttons.addView(runButton, LinearLayout.LayoutParams(0, dp(52), 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(10) })
        content.addView(buttons)

        content.addView(TextView(this).apply {
            text = "OUTPUT LOG"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(0, dp(20), 0, dp(8))
        })
        progressLabel = TextView(this).apply {
            text = "Download progress: waiting for segments…"
            textSize = 12f
            setTextColor(fg)
            setPadding(0, dp(8), 0, dp(4))
        }
        content.addView(progressLabel)
        progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = true
        }
        content.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { bottomMargin = dp(10) })
        output = TextView(this).apply {
            text = "Preparing ARM64 engine…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(fg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF191A21.toInt())
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            isFillViewport = true
            isNestedScrollingEnabled = true
            setBackgroundColor(0xFF191A21.toInt())
            addView(output)
        }
        content.addView(logScroll, LinearLayout.LayoutParams(-1, dp(260)))
        content.addView(TextView(this).apply {
            text = "Completed files are copied to the public Downloads/N_m3u8DL-RE folder."
            textSize = 12f
            setTextColor(green)
            setPadding(0, dp(12), 0, dp(18))
        })
        setContentView(page)
    }

    private fun field(parent: LinearLayout, label: String, hint: String, multiline: Boolean = false): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(fg)
            setPadding(0, dp(8), 0, dp(6))
        })
        val edit = EditText(this).apply {
            textSize = 14f
            setTextColor(fg)
            setHintTextColor(0xFF9A9AAA.toInt())
            setSingleLine(!multiline)
            this.hint = hint
            if (multiline) {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(panel)
        }
        parent.addView(edit, LinearLayout.LayoutParams(-1, if (multiline) dp(112) else dp(56)).apply { bottomMargin = dp(4) })
        return edit
    }

    private fun syncQualityArguments(position: Int) {
        val selector = when (position) {
            1 -> "best"
            2 -> "res=^1280x720:for=best"
            3 -> "res=^854x480:for=best"
            4 -> "res=^640x360:for=best"
            else -> "res=^426x240:for=best"
        }
        // Keep key and other user options, but replace any prior video/audio selectors.
        val raw = argumentsInput.text.toString()
        val withoutVideo = raw.replace(Regex("""(?m)(?:^|\s)(?:-sv|--select-video)\s+(?:"[^"]*"|'[^']*'|\S+)"""), " ")
        val withoutAudio = withoutVideo.replace(Regex("""(?m)(?:^|\s)(?:-sa|--select-audio)\s+(?:"[^"]*"|'[^']*'|\S+)"""), " ")
        argumentsInput.setText((withoutAudio.trim() + " -sv " + selector + " -sa best").trim())
        argumentsInput.setSelection(argumentsInput.text.length)
    }

    private fun startDownload() {
        val url = urlInput.text.toString().trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            appendLog("Enter a valid http:// or https:// MPD / playlist URL.")
            return
        }
        val binary = File(applicationInfo.nativeLibraryDir, "libN_m3u8DL-RE.so")
        if (!binary.exists() || !binary.canExecute()) {
            appendLog("ARM64 engine is missing or not executable. Reinstall the latest APK.")
            return
        }

        val userArgs = try { parseArguments(argumentsInput.text.toString()) } catch (e: IllegalArgumentException) {
            appendLog("Arguments error: ${e.message}")
            return
        }
        if (userArgs.any { it == url || it.startsWith("http://") || it.startsWith("https://") }) {
            appendLog("Put the manifest URL in the URL field, not in Arguments.")
            return
        }
        // Always override user-supplied audio selection so the best audio rendition is selected.
        val effectiveArgs = userArgs.toMutableList()
        var audioIndex = 0
        while (audioIndex < effectiveArgs.size) {
            if (effectiveArgs[audioIndex] == "-sa" || effectiveArgs[audioIndex] == "--select-audio") {
                effectiveArgs.removeAt(audioIndex)
                if (audioIndex < effectiveArgs.size) effectiveArgs.removeAt(audioIndex)
            } else {
                audioIndex++
            }
        }
        effectiveArgs.addAll(listOf("-sa", "best"))

        downloadDir.deleteRecursively()
        downloadDir.mkdirs()
        val engineTmpDir = File(cacheDir, "n_m3u8dl_tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg-launcher.so")
        val mp4decrypt = File(applicationInfo.nativeLibraryDir, "libmp4decrypt.so")
        if (!ffmpeg.canExecute() || !mp4decrypt.canExecute()) {
            appendLog("FFmpeg launcher or the actual mp4decrypt executable is missing/not executable in Android nativeLibraryDir.")
            return
        }
        val mp4decryptPath = mp4decrypt.canonicalPath
        val mp4decryptLookup = File(nativeDir, "mp4decrypt")
        if (!File(mp4decryptPath).isFile || !File(mp4decryptPath).canExecute() ||
            !mp4decryptLookup.isFile || !mp4decryptLookup.canExecute() ||
            mp4decryptLookup.canonicalFile != mp4decrypt.canonicalFile) {
            appendLog("mp4decrypt PATH shim is missing or invalid. Reopen the app to rerun native-tool setup.")
            return
        }
        val args = mutableListOf(binary.absolutePath, url)
        args.addAll(effectiveArgs)
        // Keep the explicit path for engine versions that honor it. The bundled
        // v0.6.0-beta build ignores it for MP4DECRYPT, so the working-directory
        // symlink named "mp4decrypt" above is the compatibility path it actually finds.
        args.addAll(listOf(
            "--save-dir=${downloadDir.absolutePath}",
            "--tmp-dir=${engineTmpDir.absolutePath}",
            "--ffmpeg-binary-path=${ffmpeg.absolutePath}",
            "--decryption-engine=MP4DECRYPT",
            "--decryption-binary-path=$mp4decryptPath",
            "--no-log",
            "--force-ansi-console",
            "--ui-language=en-US"
        ))

        runButton.isEnabled = false
        stopButton.isEnabled = true
        appendLog("\nStarting download in background… (key values are hidden)")
        appendLog("mp4decrypt engine lookup: ${mp4decryptLookup.absolutePath} -> $mp4decryptPath")
        val serviceIntent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putStringArrayListExtra(DownloadService.EXTRA_ARGS, ArrayList(args))
            putExtra(DownloadService.EXTRA_WORK_DIR, nativeDir.absolutePath)
            putExtra(DownloadService.EXTRA_HOME, filesDir.absolutePath)
            putExtra(DownloadService.EXTRA_TMP, cacheDir.absolutePath)
            putExtra(DownloadService.EXTRA_NATIVE_DIR, applicationInfo.nativeLibraryDir)
            putExtra(DownloadService.EXTRA_DOWNLOAD_DIR, downloadDir.absolutePath)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(serviceIntent) else startService(serviceIntent)
        } catch (e: Exception) {
            appendLog("Could not start background download service: ${e.message}")
            runButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun parseArguments(raw: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var started = false
        for (ch in raw) {
            if (quote != null) {
                if (ch == quote) quote = null else token.append(ch)
            } else when {
                ch == '"' || ch == '\'' -> { quote = ch; started = true }
                ch.isWhitespace() -> if (started) { result.add(token.toString()); token.setLength(0); started = false }
                else -> { token.append(ch); started = true }
            }
        }
        if (quote != null) throw IllegalArgumentException("Unclosed quote.")
        if (started) result.add(token.toString())
        return result
    }
    private fun publishDownloads(root: File): Int {
        if (!root.exists()) return 0
        var published = 0
        val files = root.walkTopDown().filter { it.isFile }.toList()
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
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create Downloads entry for ${file.name}")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
                    ?: throw IllegalStateException("Could not write ${file.name} to Downloads")
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                contentResolver.update(uri, ready, null, null)
                published++
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        }
        return published
    }

    private fun installNativePayload() {
        nativeDir.mkdirs()
        copyAssets("native", nativeDir)
    }

    private fun copyAssets(path: String, destination: File) {
        val entries = assets.list(path) ?: return
        for (entry in entries) {
            val childPath = "$path/$entry"
            val target = File(destination, entry)
            val children = assets.list(childPath)
            if (children != null && children.isNotEmpty()) {
                target.mkdirs()
                copyAssets(childPath, target)
            } else {
                assets.open(childPath).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                if (entry == "ffmpeg" || entry == "mp4decrypt" || entry == "shaka-packager") target.setExecutable(true, true)
            }
        }
    }

    private fun updateDownloadProgress(percent: Int) {
        val value = percent.coerceIn(0, 100)
        progressBar.isIndeterminate = false
        progressBar.progress = value
        progressLabel.text = "Segments downloaded: ${value}%"
    }

    private fun appendLog(line: String) {
        val wasAtBottom = logScroll.scrollY + logScroll.height >= (logScroll.getChildAt(0)?.height ?: 0) - dp(24)
        output.append(line + "\n")
        if (wasAtBottom) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
