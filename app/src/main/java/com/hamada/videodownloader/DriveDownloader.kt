package com.hamada.videodownloader

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * تنزيل ملفات Google Drive المشتركة التي يسمح صاحبها بتنزيلها
 * (ملفات Drive العادية + مستندات/جداول/عروض Google بصيغة PDF/XLSX).
 * لا يتجاوز الملفات الخاصة أو التي منع صاحبها التنزيل.
 */
object DriveDownloader {

    class DriveException(message: String) : Exception(message)
    class CancelledByUser : Exception("cancelled")

    data class Target(val url: String, val fallbackName: String)

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    fun isDriveLink(link: String): Boolean =
        "drive.google.com" in link || "docs.google.com" in link || "drive.usercontent.google.com" in link

    fun isFolder(link: String): Boolean = "/folders/" in link

    fun parse(link: String): Target? {
        val fileId = Regex("drive\\.google\\.com/file/d/([\\w-]+)").find(link)?.groupValues?.get(1)
            ?: Regex("(?:drive|docs)\\.google\\.com/(?:open|uc)\\?(?:[^#]*&)?id=([\\w-]+)").find(link)?.groupValues?.get(1)
            ?: Regex("drive\\.usercontent\\.google\\.com/[^?]*\\?(?:[^#]*&)?id=([\\w-]+)").find(link)?.groupValues?.get(1)
        if (fileId != null) {
            return Target(
                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t",
                "drive_$fileId"
            )
        }
        val doc = Regex("docs\\.google\\.com/(document|spreadsheets|presentation)/d/([\\w-]+)").find(link)
        if (doc != null) {
            val type = doc.groupValues[1]
            val id = doc.groupValues[2]
            val fmt = if (type == "spreadsheets") "xlsx" else "pdf"
            return Target("https://docs.google.com/$type/d/$id/export?format=$fmt", "${type}_$id.$fmt")
        }
        return null
    }

    /**
     * @param onProgress (نسبة 0..100 أو -1 إذا الحجم غير معروف, عدد البايتات المنزّلة)
     */
    fun download(
        target: Target,
        outDir: File,
        onProgress: (Int, Long) -> Unit,
        isCancelled: () -> Boolean
    ): File {
        var url = target.url
        // محاولة ثانية فقط في حال ظهرت صفحة تأكيد "تعذّر فحص الفيروسات" للملفات الكبيرة
        for (attempt in 0..1) {
            val conn = open(url)
            try {
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    throw DriveException("هذا الملف خاص أو صاحبه منع التنزيل، ولا يمكن تنزيله.")
                }
                if (code == 404) throw DriveException("الملف غير موجود أو تم حذفه.")
                if (code == 429) throw DriveException("تجاوز الملف حد التنزيل اليومي من Google. حاول لاحقاً.")
                if (code !in 200..299) throw DriveException("رد غير متوقع من Google Drive (رمز $code).")

                val type = conn.contentType.orEmpty()
                if (type.startsWith("text/html")) {
                    val html = conn.inputStream.bufferedReader().use { it.readText() }
                    val uuid = Regex("name=\"uuid\"\\s+value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                    if (attempt == 0 && uuid != null && "drive.usercontent" in url) {
                        url = "$url&uuid=$uuid"
                        continue
                    }
                    if ("quota" in html.lowercase() || "too many users" in html.lowercase()) {
                        throw DriveException("تجاوز الملف حد التنزيل اليومي من Google. حاول لاحقاً.")
                    }
                    throw DriveException(
                        "لا يمكن تنزيل هذا الملف: إما أنه خاص، أو أن صاحبه منع التنزيل، أو يحتاج تسجيل دخول.\n" +
                            "تأكد أن الملف مشترك بخيار \"أي شخص لديه الرابط\" وأن التنزيل مسموح."
                    )
                }

                val name = uniqueFile(outDir, sanitize(fileNameFrom(conn) ?: target.fallbackName))
                val part = File(outDir, name.name + ".part")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPct = -2
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw CancelledByUser()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                            if (pct != lastPct || pct == -1 && done % (1024 * 1024) < n) {
                                lastPct = pct
                                onProgress(pct, done)
                            }
                        }
                    }
                }
                if (!part.renameTo(name)) {
                    part.copyTo(name, overwrite = true)
                    part.delete()
                }
                return name
            } catch (e: CancelledByUser) {
                outDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                throw e
            } finally {
                conn.disconnect()
            }
        }
        throw DriveException("تعذّر تنزيل الملف من Google Drive.")
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", UA)
        return conn
    }

    private fun fileNameFrom(conn: HttpURLConnection): String? {
        val cd = conn.getHeaderField("Content-Disposition") ?: return null
        Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
            return try {
                URLDecoder.decode(it.groupValues[1].trim(), "UTF-8")
            } catch (_: Exception) {
                null
            }
        }
        Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(cd)?.let {
            return it.groupValues[1].trim()
        }
        return null
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        return clean.ifEmpty { "drive_file" }.take(150)
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "$base ($i)$ext")
            i++
        }
        return f
    }
}
