package com.naksh.vibeaudio

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.Base64
import android.webkit.CookieManager
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OfflineAudioStore {

    private const val downloadsDirectoryName = "offline_audio"
    private const val localBaseUrl = "https://appassets.androidplatform.net/local/$downloadsDirectoryName"
    private const val connectTimeoutMs = 15_000
    private const val readTimeoutMs = 30_000
    private const val maxRedirects = 5
    private const val httpTempRedirect = 307
    private const val httpPermRedirect = 308

    fun resolveVirtualUrl(context: Context, requestedFileName: String): String? {
        val file = resolveFile(context, requestedFileName) ?: return null
        if (!file.exists() || !file.isFile) {
            return null
        }

        val relativePath = sanitizeRelativePath(requestedFileName) ?: return null
        return "$localBaseUrl/${encodePath(relativePath)}"
    }

    fun delete(context: Context, requestedFileName: String): Boolean {
        val file = resolveFile(context, requestedFileName) ?: return false
        return file.exists() && file.delete()
    }

    fun download(
        context: Context,
        sourceUrl: String,
        requestedFileName: String,
        userAgent: String?,
        referer: String?
    ): Result<String> {
        return runCatching {
            require(sourceUrl.isNotBlank()) { "Download URL missing hai" }
            require(!sourceUrl.startsWith("blob:")) { "Blob URL ko pehle base64 me convert karna padega" }
            require(!sourceUrl.startsWith("data:")) { "Data URL ko saveBase64 path se handle karna padega" }

            val destinationFile = resolveFile(context, requestedFileName)
                ?: error("Invalid file name: $requestedFileName")
            val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.part")

            if (tempFile.exists()) {
                tempFile.delete()
            }

            val connection = openConnection(sourceUrl, userAgent, referer)
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                    error("Download failed with HTTP $responseCode")
                }

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (destinationFile.exists() && !destinationFile.delete()) {
                error("Could not replace existing file: ${destinationFile.name}")
            }

            if (!tempFile.renameTo(destinationFile)) {
                error("Could not finalize download for ${destinationFile.name}")
            }

            resolveVirtualUrl(context, requestedFileName)
                ?: error("Downloaded file could not be resolved")
        }.onFailure { error ->
            resolveFile(context, requestedFileName)
                ?.parentFile
                ?.let { File(it, "${File(requestedFileName).name}.part") }
                ?.takeIf(File::exists)
                ?.delete()
            Log.e("OfflineAudioStore", "Download failed for $sourceUrl", error)
        }
    }

    fun saveBase64(
        context: Context,
        base64Payload: String,
        requestedFileName: String
    ): Result<String> {
        return runCatching {
            require(base64Payload.isNotBlank()) { "Base64 audio payload missing hai" }

            val destinationFile = resolveFile(context, requestedFileName)
                ?: error("Invalid file name: $requestedFileName")
            val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.part")
            val bytes = Base64.decode(base64Payload.trim(), Base64.DEFAULT)

            if (tempFile.exists()) {
                tempFile.delete()
            }

            FileOutputStream(tempFile).use { output ->
                output.write(bytes)
                output.flush()
            }

            if (destinationFile.exists() && !destinationFile.delete()) {
                error("Could not replace existing file: ${destinationFile.name}")
            }

            if (!tempFile.renameTo(destinationFile)) {
                error("Could not finalize download for ${destinationFile.name}")
            }

            resolveVirtualUrl(context, requestedFileName)
                ?: error("Downloaded file could not be resolved")
        }.onFailure { error ->
            resolveFile(context, requestedFileName)
                ?.parentFile
                ?.let { File(it, "${File(requestedFileName).name}.part") }
                ?.takeIf(File::exists)
                ?.delete()
            Log.e("OfflineAudioStore", "Base64 save failed for $requestedFileName", error)
        }
    }

    private fun openConnection(
        sourceUrl: String,
        userAgent: String?,
        referer: String?
    ): HttpURLConnection {
        var currentUrl = sourceUrl
        repeat(maxRedirects + 1) { attempt ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                doInput = true
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")

                if (!userAgent.isNullOrBlank()) {
                    setRequestProperty("User-Agent", userAgent)
                }

                if (!referer.isNullOrBlank()) {
                    setRequestProperty("Referer", referer)
                    runCatching {
                        referer.toUri().buildUpon()
                            .encodedPath("")
                            .encodedQuery(null)
                            .fragment(null)
                            .build()
                            .toString()
                    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { origin ->
                        setRequestProperty("Origin", origin)
                    }
                }

                val cookieHeader = buildCookieHeader(currentUrl, referer)
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    httpTempRedirect,
                    httpPermRedirect
                )
            ) {
                val location = connection.getHeaderField("Location")
                if (location.isNullOrBlank() || attempt >= maxRedirects) {
                    return connection
                }

                currentUrl = URL(URL(currentUrl), location).toString()
                connection.disconnect()
            } else {
                return connection
            }
        }

        error("Too many redirects for $sourceUrl")
    }

    private fun buildCookieHeader(requestUrl: String, referer: String?): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val cookieValues = linkedSetOf<String>()
        listOf(requestUrl, referer)
            .filterNotNull()
            .forEach { url ->
                cookieManager.getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(cookieValues::add)
            }

        return cookieValues.joinToString("; ")
    }

    private fun resolveFile(context: Context, requestedFileName: String): File? {
        val relativePath = sanitizeRelativePath(requestedFileName) ?: return null
        val rootDirectory = File(context.filesDir, downloadsDirectoryName).canonicalFile
        val targetFile = File(rootDirectory, relativePath).canonicalFile

        if (!targetFile.path.startsWith(rootDirectory.path)) {
            return null
        }

        targetFile.parentFile?.mkdirs()
        return targetFile
    }

    private fun sanitizeRelativePath(requestedFileName: String): String? {
        val normalized = requestedFileName
            .replace('\\', '/')
            .trim()
            .removePrefix("/")

        if (normalized.isBlank()) {
            return null
        }

        val segments = normalized
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }

        if (segments.isEmpty()) {
            return null
        }

        return segments.joinToString("/")
    }

    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment ->
            Uri.encode(segment)
        }
    }
}
