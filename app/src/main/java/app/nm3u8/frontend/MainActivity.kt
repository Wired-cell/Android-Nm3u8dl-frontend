package app.nm3u8.frontend

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var process: Process? = null
    private lateinit var urlInput: EditText
    private lateinit var nameInput: EditText
    private lateinit var headersInput: EditText
    private lateinit var keysInput: EditText
    private lateinit var optionsInput: EditText
    private lateinit var output: TextView
    private lateinit var runButton: Button
    private lateinit var stopButton: Button
    private lateinit var nativeDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nativeDir = File(filesDir, "native")
        buildUi()
        executor.execute {
            try {
                installNativePayload()
                val binary = File(nativeDir, "N_m3u8DL-RE")
                val check = ProcessBuilder(binary.absolutePath, "--version")
                    .directory(nativeDir).redirectErrorStream(true).start()
                val version = check.inputStream.bufferedReader().use { it.readText() }.trim()
                val code = check.waitFor()
                runOnUiThread {
                    appendLog(if (code == 0) "Engine ready: $version" else "Engine returned exit code $code. Check bundled runtime files.")
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
            setBackgroundColor(0xFFF5F6FA.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "N_m3u8DL-RE"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF172033.toInt())
        })
        content.addView(TextView(this).apply {
            text = "Android frontend • ARM64"
            textSize = 14f
            setTextColor(0xFF667085.toInt())
            setPadding(0, dp(3), 0, dp(18))
        })

        urlInput = field(content, "Playlist URL (HLS / DASH / MSS)", "https://example.com/playlist.m3u8")
        nameInput = field(content, "Output file name (optional)", "video")
        headersInput = field(content, "HTTP headers (one per line)", "User-Agent: Mozilla/5.0\nReferer: https://example.com", true)
        keysInput = field(content, "Decryption keys (one per line)", "KID:KEY or KEY — only keys you are authorized to use", true).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        content.addView(TextView(this).apply {
            text = "Each line is passed as a separate --key argument. Supports KID:KEY pairs or a single KEY. Key values are hidden in this field and omitted from the app's command log."
            textSize = 11f
            setTextColor(0xFF667085.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        optionsInput = field(content, "Extra N_m3u8DL-RE options", "--auto-select -mt", true)

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
            setTextColor(0xFF475467.toInt())
            setPadding(0, dp(20), 0, dp(8))
        })
        output = TextView(this).apply {
            text = "Preparing ARM64 engine…"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE6EDF3.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF172033.toInt())
            setTextIsSelectable(true)
        }
        content.addView(output, LinearLayout.LayoutParams(-1, dp(220)))
        content.addView(TextView(this).apply {
            text = "Downloads are saved in this app's private Downloads folder. Use content you are authorized to access."
            textSize = 12f
            setTextColor(0xFF667085.toInt())
            setPadding(0, dp(12), 0, dp(18))
        })
        setContentView(page)
    }

    private fun field(parent: LinearLayout, label: String, hint: String, multiline: Boolean = false): EditText {
        parent.addView(TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF344054.toInt())
            setPadding(0, dp(8), 0, dp(6))
        })
        val edit = EditText(this).apply {
            textSize = 14f
            setSingleLine(!multiline)
            this.hint = hint
            if (multiline) {
                minLines = 2
                maxLines = 5
                gravity = Gravity.TOP or Gravity.START
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        parent.addView(edit, LinearLayout.LayoutParams(-1, if (multiline) dp(104) else dp(52)).apply { bottomMargin = dp(4) })
        return edit
    }

    private fun startDownload() {
        val url = urlInput.text.toString().trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            appendLog("Enter a valid http:// or https:// playlist URL.")
            return
        }
        val binary = File(nativeDir, "N_m3u8DL-RE")
        if (!binary.exists()) { appendLog("ARM64 engine is missing."); return }

        val args = mutableListOf(binary.absolutePath, url, "--save-dir", File(getExternalFilesDir(null), "Downloads").apply { mkdirs() }.absolutePath, "--ui-language", "en-US")
        nameInput.text.toString().trim().takeIf { it.isNotEmpty() }?.let { args.addAll(listOf("--save-name", it)) }
        headersInput.text.toString().lineSequence().map { it.trim() }.filter { it.contains(":") }.forEach {
            args.add("-H"); args.add(it)
        }
        val suppliedKeys = keysInput.text.toString().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        suppliedKeys.forEach { key ->
            if (key.any { it.isWhitespace() }) {
                appendLog("Each key must be a single token. Use one KID:KEY or KEY value per line.")
                return
            }
            args.add("--key")
            args.add(key)
        }
        args.addAll(parseArguments(optionsInput.text.toString()))
        runButton.isEnabled = false
        stopButton.isEnabled = true
        appendLog("\n> Download command started (decryption key values are not displayed).")
        executor.execute {
            try {
                val builder = ProcessBuilder(args).directory(nativeDir).redirectErrorStream(true)
                builder.environment()["HOME"] = filesDir.absolutePath
                builder.environment()["TMPDIR"] = cacheDir.absolutePath
                process = builder.start()
                process!!.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> runOnUiThread { appendLog(line) } }
                }
                val code = process!!.waitFor()
                runOnUiThread { appendLog("Process finished (exit $code)."); runButton.isEnabled = true; stopButton.isEnabled = false; process = null }
            } catch (e: Exception) {
                runOnUiThread { appendLog("Run failed: ${e.message}"); runButton.isEnabled = true; stopButton.isEnabled = false }
            }
        }
    }

    private fun parseArguments(input: String): List<String> {
        val result = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in input) {
            if (escaped) { token.append(c); escaped = false; continue }
            if (c == '\\' && quote != null) { escaped = true; continue }
            if ((c == '"' || c == '\'') && (quote == null || quote == c)) {
                quote = if (quote == null) c else null
                continue
            }
            if (c.isWhitespace() && quote == null) {
                if (token.isNotEmpty()) { result.add(token.toString()); token.setLength(0) }
            } else token.append(c)
        }
        if (token.isNotEmpty()) result.add(token.toString())
        return result
    }

    private fun installNativePayload() {
        if (File(nativeDir, "N_m3u8DL-RE").exists()) return
        nativeDir.mkdirs()
        copyAssets("native", nativeDir)
        val binary = File(nativeDir, "N_m3u8DL-RE")
        if (!binary.exists()) throw IllegalStateException("APK does not contain the ARM64 N_m3u8DL-RE binary")
        binary.setExecutable(true, true)
        nativeDir.listFiles()?.filter { it.isFile && (it.name == "ffmpeg" || it.name == "mp4decrypt" || it.name == "shaka-packager") }?.forEach { it.setExecutable(true, true) }
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
                if (entry == "N_m3u8DL-RE" || entry == "ffmpeg" || entry == "mp4decrypt" || entry == "shaka-packager") target.setExecutable(true, true)
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
