package app.nm3u8.frontend

import android.app.Activity
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
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private lateinit var urlInput: EditText
    private lateinit var argumentsInput: EditText
    private lateinit var qualitySpinner: Spinner
    private lateinit var output: TextView
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
                val decryptBuilder = ProcessBuilder(mp4decrypt.absolutePath, "--help")
                    .directory(nativeDir).redirectErrorStream(true)
                // Bento4 mp4decrypt is built with a static C++ runtime, so it can
                // execute directly without the FFmpeg-specific launcher.
                val decryptCheck = decryptBuilder.start()
                val decryptOutput = decryptCheck.inputStream.bufferedReader().readText()
                val decryptCode = decryptCheck.waitFor()
                if (decryptCode != 0 && decryptOutput.isBlank()) {
                    throw IllegalStateException("mp4decrypt launch test failed (code $decryptCode).")
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
            setOnClickListener { process?.destroy() }
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
        output = TextView(this).apply {
            text = "Preparing ARM64 engine…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(fg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF191A21.toInt())
            setTextIsSelectable(true)
        }
        content.addView(output, LinearLayout.LayoutParams(-1, dp(220)))
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
        val ffmpeg = File(applicationInfo.nativeLibraryDir, "libffmpeg-launcher.so")
        val mp4decrypt = File(applicationInfo.nativeLibraryDir, "libmp4decrypt.so")
        if (!ffmpeg.canExecute() || !mp4decrypt.canExecute()) {
            appendLog("FFmpeg launcher or the actual mp4decrypt executable is missing/not executable in Android nativeLibraryDir.")
            return
        }
        val mp4decryptPath = mp4decrypt.canonicalPath
        if (!File(mp4decryptPath).isFile || !File(mp4decryptPath).canExecute()) {
            appendLog("mp4decrypt preflight failed: $mp4decryptPath")
            return
        }
        val args = mutableListOf(binary.absolutePath, url)
        args.addAll(effectiveArgs)
        // Use --option=value tokens so System.CommandLine receives the binary path
        // as the option value even when the positional manifest URL and user args
        // are present. The upstream engine only searches PATH when this option is null.
        args.addAll(listOf(
            "--save-dir=${downloadDir.absolutePath}",
            "--ffmpeg-binary-path=${ffmpeg.absolutePath}",
            "--decryption-engine=MP4DECRYPT",
            "--decryption-binary-path=$mp4decryptPath",
            "--no-log",
            "--ui-language=en-US"
        ))

        runButton.isEnabled = false
        stopButton.isEnabled = true
        appendLog("\nStarting download… (key values are hidden)")
        appendLog("Configured mp4decrypt executable: $mp4decryptPath")
        executor.execute {
            try {
                val builder = ProcessBuilder(args).directory(nativeDir).redirectErrorStream(true)
                val environment = builder.environment()
                environment["HOME"] = filesDir.absolutePath
                environment["TMPDIR"] = cacheDir.absolutePath
                environment["PATH"] = "${applicationInfo.nativeLibraryDir}:${nativeDir.absolutePath}:${environment["PATH"].orEmpty()}"
                // Prefer the matching OpenSSL libraries shipped with the Android Bionic engine.
                environment["LD_LIBRARY_PATH"] = "${applicationInfo.nativeLibraryDir}:${nativeDir.absolutePath}:${environment["LD_LIBRARY_PATH"].orEmpty()}"
                process = builder.start()
                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> runOnUiThread { appendLog(line) } }
                }
                val code = process!!.waitFor()
                if (code == 0) {
                    val count = publishDownloads(downloadDir)
                    runOnUiThread { appendLog("Finished. Published $count file(s) to Downloads/N_m3u8DL-RE.") }
                } else {
                    runOnUiThread { appendLog("Downloader exited with code $code. No files were published.") }
                }
                runOnUiThread { runButton.isEnabled = true; stopButton.isEnabled = false; process = null }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Run failed: ${e.message}"); runButton.isEnabled = true; stopButton.isEnabled = false; process = null }
            }
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

    private fun appendLog(line: String) {
        output.append(line + "\n")
        val layout = output.layout
        if (layout != null) {
            val scrollAmount = layout.getLineTop(output.lineCount) - output.height
            if (scrollAmount > 0) output.scrollTo(0, scrollAmount) else output.scrollTo(0, 0)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        process?.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }
}
