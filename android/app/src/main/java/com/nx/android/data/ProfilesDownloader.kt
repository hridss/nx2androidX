package com.nx.android.data

import com.nx.android.domain.ProfilesPackage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The game profiles package, fetched over the network.
 *
 * The download lands in a .part file and is only put in place when it is whole: a package that was cut short
 * never reaches the importer. What makes a package whole is its length - the server says how many bytes it
 * sends, and a download that came up short was cut off (a connection that dies mid-way ends the stream
 * quietly, so the count is the only thing that tells).
 *
 * The digest is not checked here: the package that comes back is not necessarily the one this build made
 * (a local build's package differs from the release's while carrying the same name), and a package that is
 * not this build's is still a usable one - the importer takes it and the checklist says which one it is.
 */
object ProfilesDownloader {

    /** Why a download did not finish: the connection was cut before the server had sent everything */
    private const val CUT_SHORT = "the download was cut short"

    /** The names of the packages a release carries (its assets page is a plain web page) */
    fun packagesOf(releaseTag: String): List<String> {
        val html = body(ProfilesPackage.assetsPage(releaseTag)) ?: return emptyList()
        return ProfilesPackage.packageNamesIn(html)
    }

    /**
     * Downloads a package into [dest].
     *
     * A download that was cut short is tried once more: the line to the release drops long transfers now
     * and then, and the package is big enough for that to happen. The second attempt is the last one - a
     * package that does not arrive twice is not going to arrive.
     *
     * @param onProgress how far along it is, 0 to 1
     * @param cancelled whether the download was called off; it stops at the next chunk
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
        cancelled: () -> Boolean,
    ): Result<File> {
        val tmp = File(dest.path + ".part")
        return runCatching {
            tmp.parentFile?.mkdirs()
            var attempt = 0
            while (true) {
                try {
                    fetch(url, tmp, onProgress, cancelled)
                    break
                } catch (e: IOException) {
                    if (e.message != CUT_SHORT || attempt > 0 || cancelled()) throw e
                    attempt++
                }
            }
            if (dest.exists() && !dest.delete()) throw IOException("the old package could not be replaced")
            if (!tmp.renameTo(dest)) throw IOException("the package could not be put in place")
            dest
        }.also { if (it.isFailure) tmp.delete() }
    }

    /**
     * One transfer: the bytes land in [tmp].
     *
     * A stream that ends before the server's length is reached is a download that was cut short - the
     * connection ending quietly looks like the end of the file, so the count is what tells the two apart.
     */
    private fun fetch(url: String, tmp: File, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("User-Agent", "nx2android")
        }
        var total = 0L
        var done = 0L
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            total = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            try {
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) throw IOException("cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: IOException) {
                // The transfer died in the middle (a reset, a closed socket): fewer bytes came than the
                // server promised, which is a cut-short download.
                if (cancelled()) throw e
                throw IOException(CUT_SHORT, e)
            }
            if (total > 0 && done < total) throw IOException(CUT_SHORT)
        } finally {
            conn.disconnect()
        }
    }

    /** A page's body (the asset list is a small one); null when the server did not answer */
    private fun body(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "nx2android")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (e: IOException) {
        null
    }
}
