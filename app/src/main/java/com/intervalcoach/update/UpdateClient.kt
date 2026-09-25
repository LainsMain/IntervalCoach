package com.intervalcoach.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

private const val RELEASE_API = "https://api.github.com/repos/LainsMain/IntervalCoach/releases/latest"
private const val MAX_APK_BYTES = 250L * 1024 * 1024

data class ReleaseInfo(val version: String, val tag: String, val downloadUrl: String, val sha256: String, val size: Long)

/** Only stable numeric release tags are installable. */
fun versionParts(value: String): List<Int>? {
    val clean = value.removePrefix("v")
    if (!clean.matches(Regex("[0-9]+(\\.[0-9]+){1,3}"))) return null
    return clean.split('.').map { it.toIntOrNull() ?: return null }
}
fun isNewerVersion(candidate: String, installed: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(installed) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val difference = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (difference != 0) return difference > 0
    }
    return false
}

fun parseLatestRelease(json: String): ReleaseInfo {
    val root = JSONObject(json)
    val tag = root.getString("tag_name")
    val version = tag.removePrefix("v")
    require(versionParts(tag) != null) { "Release version is invalid" }
    val assets = root.getJSONArray("assets")
    for (i in 0 until assets.length()) {
        val asset = assets.getJSONObject(i)
        val name = asset.getString("name")
        if (!name.matches(Regex("IntervalCoach-v[0-9]+(\\.[0-9]+){1,3}\\.apk"))) continue
        if (name != "IntervalCoach-$tag.apk") continue
        val url = asset.getString("browser_download_url")
        require(url.startsWith("https://github.com/LainsMain/IntervalCoach/releases/download/")) { "Unexpected release URL" }
        val digest = asset.optString("digest").removePrefix("sha256:")
        require(digest.matches(Regex("[0-9a-fA-F]{64}"))) { "Release checksum is unavailable" }
        val size = asset.getLong("size")
        require(size in 1..MAX_APK_BYTES) { "Release APK size is invalid" }
        return ReleaseInfo(version, tag, url, digest.lowercase(), size)
    }
    error("Release has no Android APK")
}

class UpdateClient(private val context: Context) {
    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "IntervalCoach-Android")
        }
        try {
            when (connection.responseCode) {
                200 -> parseLatestRelease(connection.inputStream.bufferedReader().use { it.readText() })
                404 -> error("No release is published yet")
                else -> error("GitHub returned ${connection.responseCode}")
            }
        } finally { connection.disconnect() }
    }

    suspend fun download(release: ReleaseInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "IntervalCoach-update.partial.apk")
        val apk = File(directory, "IntervalCoach-update.apk")
        partial.delete()
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "IntervalCoach-Android")
        }
        try {
            require(connection.responseCode == 200 && connection.url.protocol == "https") { "APK download failed" }
            val digest = MessageDigest.getInstance("SHA-256")
            var count = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        require(count <= MAX_APK_BYTES && count <= release.size) { "APK is larger than expected" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress((count * 100 / release.size).toInt().coerceIn(0, 100))
                    }
                }
            }
            require(count == release.size) { "APK download was incomplete" }
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
            require(actualDigest == release.sha256) { "APK checksum did not match" }
            val archive = context.packageManager.getPackageArchiveInfo(partial.path, 0)
            require(archive?.packageName == context.packageName) { "APK is for a different app" }
            val current = context.packageManager.getPackageInfo(context.packageName, 0)
            require(PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(current)) { "APK is not newer than this app" }
            apk.delete()
            require(partial.renameTo(apk)) { "Could not save the APK" }
            apk
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally { connection.disconnect() }
    }
}
