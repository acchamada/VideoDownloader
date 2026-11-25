package com.hamada.videodownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: TextInputEditText
    private lateinit var qualityGroup: RadioGroup
    private lateinit var downloadBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton
    private lateinit var updateBtn: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var versionText: TextView

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var engineReady = false

    @Volatile
    private var busy = false

    @Volatile
    private var cancelled = false

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        qualityGroup = findViewById(R.id.qualityGroup)
        downloadBtn = findViewById(R.id.downloadBtn)
        cancelBtn = findViewById(R.id.cancelBtn)
        updateBtn = findViewById(R.id.updateBtn)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.statusText)
        versionText = findViewById(R.id.versionText)

        findViewById<MaterialButton>(R.id.pasteBtn).setOnClickListener { pasteFromClipboard() }
        findViewById<MaterialButton>(R.id.clearBtn).setOnClickListener {
            urlInput.setText("")
            statusText.text = ""
        }
        findViewById<MaterialButton>(R.id.openFolderBtn).setOnClickListener { openDownloads() }
        downloadBtn.setOnClickListener { startDownload() }
        cancelBtn.setOnClickListener { cancelDownload() }
        updateBtn.setOnClickListener { updateEngine(manual = true) }

        askStoragePermissionIfNeeded()
        initEngine()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    // ---------- Engine (yt-dlp + python + ffmpeg) ----------

    private fun initEngine() {
        if (engineReady) return
        setStatus("جاري تجهيز محرك التنزيل لأول مرة… قد يستغرق دقيقة")
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                engineReady = true
                post {
                    setStatus("جاهز ✅ الصق الرابط واضغط تنزيل")
                    showVersion()
                }
                // تحديث تلقائي مرة كل يوم حتى يبقى التنزيل من يوتيوب وفيسبوك شغالاً
                val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
                val last = prefs.getLong("last_update", 0L)
                if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) {
                    try {
                        YoutubeDL.getInstance()
                            .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                        prefs.edit().putLong("last_update", System.currentTimeMillis()).apply()
                        post { showVersion() }
                    } catch (_: Throwable) {
                        // لا مشكلة؛ سيحاول مرة أخرى لاحقاً
                    }
                }
            } catch (e: Throwable) {
                post { setStatus("تعذر تجهيز المحرك: ${e.message}") }
            }
        }
    }

    private fun updateEngine(manual: Boolean) {
        if (busy) {
            toast("انتظر حتى ينتهي التنزيل الحالي")
            return
        }
        updateBtn.isEnabled = false
        setStatus("جاري تحديث محرك التنزيل…")
        worker.execute {
            try {
                val result = YoutubeDL.getInstance()
                    .updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                    .putLong("last_update", System.currentTimeMillis()).apply()
                post {
                    setStatus(
                        if (result == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)
                            "المحرك محدّث بالفعل ✅" else "تم تحديث المحرك ✅"
                    )
                    showVersion()
                }
            } catch (e: Throwable) {
                if (manual) post { setStatus("فشل التحديث (تأكد من الإنترنت): ${e.message}") }
            } finally {
                post { updateBtn.isEnabled = true }
            }
        }
    }

    private fun showVersion() {
        val v = try {
            YoutubeDL.getInstance().versionName(applicationContext)
                ?: YoutubeDL.getInstance().version(applicationContext)
        } catch (_: Throwable) {
            null
        }
        val appV = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Throwable) {
            ""
        }
        versionText.text = "إصدار التطبيق $appV • yt-dlp ${v ?: "-"}"
    }

    // ---------- Download ----------

    private fun startDownload() {
        val url = extractUrl(urlInput.text?.toString().orEmpty())
        if (url == null) {
            toast("الصق رابطاً صحيحاً يبدأ بـ http")
            return
        }
        if (busy) {
            toast("يوجد تنزيل قيد التنفيذ")
            return
        }
        busy = true
        cancelled = false
        setBusyUi(true)
        setStatus(if (engineReady) "جاري جلب معلومات الفيديو…" else "بانتظار تجهيز المحرك…")

        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "VideoDownloader"
        )
        val qualityId = qualityGroup.checkedRadioButtonId
        val startTime = System.currentTimeMillis()

        worker.execute {
            try {
                if (!engineReady) throw IllegalStateException("المحرك غير جاهز، أعد فتح التطبيق")
                if (!outDir.exists()) outDir.mkdirs()

                // ----- Google Drive -----
                if (DriveDownloader.isDriveLink(url)) {
                    if (DriveDownloader.isFolder(url)) {
                        throw DriveDownloader.DriveException(
                            "روابط المجلدات غير مدعومة. افتح الملف داخل المجلد وانسخ رابطه."
                        )
                    }
                    val target = DriveDownloader.parse(url)
                    if (target != null) {
                        post { setStatus("جاري التنزيل من Google Drive…") }
                        val file = DriveDownloader.download(
                            target, outDir,
                            { pct, bytes ->
                                post {
                                    val mb = "%.1f".format(bytes / 1048576.0)
                                    if (pct >= 0) {
                                        progress.isIndeterminate = false
                                        progress.setProgressCompat(pct, true)
                                        setStatus("جاري التنزيل من Drive $pct% • $mb ميجا")
                                    } else {
                                        setStatus("جاري التنزيل من Drive • $mb ميجا")
                                    }
                                }
                            },
                            { cancelled }
                        )
                        MediaScannerConnection.scanFile(
                            applicationContext, arrayOf(file.absolutePath), null, null
                        )
                        post { setStatus("تم التنزيل بنجاح ✅\n${file.name}\nفي: Download/VideoDownloader") }
                        return@execute
                    }
                }

                val request = YoutubeDLRequest(url)
                request.addOption("--no-mtime")
                request.addOption("--no-playlist")
                request.addOption("--newline")
                request.addOption("-o", outDir.absolutePath + "/%(title).80B [%(id)s].%(ext)s")

                when (qualityId) {
                    R.id.qAudio -> {
                        request.addOption("-f", "ba/b")
                        request.addOption("-x")
                        request.addOption("--audio-format", "mp3")
                        request.addOption("--audio-quality", "0")
                    }
                    R.id.q720 -> {
                        request.addOption(
                            "-f",
                            "bv*[height<=720][ext=mp4]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]/b"
                        )
                        request.addOption("--merge-output-format", "mp4")
                    }
                    R.id.q480 -> {
                        request.addOption(
                            "-f",
                            "bv*[height<=480][ext=mp4]+ba[ext=m4a]/bv*[height<=480]+ba/b[height<=480]/b"
                        )
                        request.addOption("--merge-output-format", "mp4")
                    }
                    else -> {
                        request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b")
                        request.addOption("--merge-output-format", "mp4")
                    }
                }

                YoutubeDL.getInstance().execute(request, PROCESS_ID) { p: Float, eta: Long, _: String ->
                    post {
                        if (p >= 0f) {
                            progress.isIndeterminate = false
                            progress.setProgressCompat(p.toInt().coerceIn(0, 100), true)
                            val etaText = if (eta > 0) " • متبقي ${formatEta(eta)}" else ""
                            setStatus("جاري التنزيل ${"%.1f".format(p)}%$etaText")
                        }
                    }
                }

                val newFiles = outDir.listFiles()
                    ?.filter { it.isFile && it.lastModified() >= startTime - 5000 && !it.name.endsWith(".part") }
                    .orEmpty()
                if (newFiles.isNotEmpty()) {
                    MediaScannerConnection.scanFile(
                        applicationContext,
                        newFiles.map { it.absolutePath }.toTypedArray(),
                        null,
                        null
                    )
                }
                val name = newFiles.maxByOrNull { it.lastModified() }?.name ?: ""
                post { setStatus("تم التنزيل بنجاح ✅\n$name\nفي: Download/VideoDownloader") }
            } catch (e: Throwable) {
                post {
                    if (cancelled || e is YoutubeDL.CanceledException || e is DriveDownloader.CancelledByUser) {
                        setStatus("تم الإلغاء")
                    } else if (e is DriveDownloader.DriveException) {
                        setStatus("فشل التنزيل ❌\n${e.message}")
                    } else {
                        setStatus(friendlyError(e))
                    }
                }
            } finally {
                busy = false
                post { setBusyUi(false) }
            }
        }
    }

    private fun cancelDownload() {
        cancelled = true
        try {
            YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
        } catch (_: Throwable) {
        }
        setStatus("جاري الإلغاء…")
    }

    private fun friendlyError(e: Throwable): String {
        val msg = (e.message ?: e.toString()).trim()
        val lower = msg.lowercase()
        val hint = when {
            "unsupported url" in lower -> "هذا الرابط غير مدعوم."
            "private" in lower || "login" in lower || "cookies" in lower || "sign in" in lower ->
                "الفيديو خاص أو يتطلب تسجيل دخول، لا يمكن تنزيله."
            "unable to resolve" in lower || "network" in lower || "timed out" in lower ->
                "تأكد من اتصال الإنترنت ثم حاول مرة أخرى."
            else -> "جرّب الضغط على \"تحديث محرك التنزيل\" ثم أعد المحاولة."
        }
        val shortMsg = msg.lines().filter { it.isNotBlank() }.takeLast(3).joinToString("\n")
        return "فشل التنزيل ❌\n$hint\n\nالتفاصيل:\n$shortMsg"
    }

    // ---------- Helpers ----------

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = extractUrl(text) ?: return
        urlInput.setText(url)
        // بدء التنزيل تلقائياً عند المشاركة من تطبيق آخر
        if (!busy) startDownload()
    }

    private fun extractUrl(text: String): String? {
        val m = Regex("https?://[^\\s]+").find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', '"', '\'')
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        val url = text?.let { extractUrl(it) }
        if (url != null) urlInput.setText(url) else toast("لا يوجد رابط في الحافظة")
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Throwable) {
            toast("الملفات في: Download/VideoDownloader")
        }
    }

    private fun setBusyUi(isBusy: Boolean) {
        downloadBtn.isEnabled = !isBusy
        cancelBtn.visibility = if (isBusy) View.VISIBLE else View.GONE
        progress.visibility = if (isBusy) View.VISIBLE else View.GONE
        if (isBusy) {
            progress.isIndeterminate = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            acquireWakeLock()
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoDownloader:dl").apply {
                acquire(60L * 60 * 1000)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    private fun askStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun formatEta(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}د ${s}ث" else "${s}ث"
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun post(block: () -> Unit) {
        mainHandler.post { if (!isDestroyed) block() }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val PROCESS_ID = "main_download"

        // خيط عمل واحد مشترك: التجهيز ثم التنزيلات بالترتيب
        private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    }
}
