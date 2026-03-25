package com.naksh.vibeaudio

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OfflineAudioStore {

    private const val downloadsDirectoryName = "offline_audio"
    private const val localBaseUrl = "https://appassets.androidplatform.net/local/$downloadsDirectoryName"
    private const val connectTimeoutMs = 15_000
    private const val readTimeoutMs = 30_000

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
            val destinationFile = resolveFile(context, requestedFileName)
                ?: error("Invalid file name: $requestedFileName")
            val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.part")

            if (tempFile.exists()) {
                tempFile.delete()
            }

            val connection = openConnection(sourceUrl, userAgent, referer)
            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
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
        }
    }

    private fun openConnection(
        sourceUrl: String,
        userAgent: String?,
        referer: String?
    ): HttpURLConnection {
        return (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            doInput = true
            setRequestProperty("Accept", "*/*")

            if (!userAgent.isNullOrBlank()) {
                setRequestProperty("User-Agent", userAgent)
            }

            if (!referer.isNullOrBlank()) {
                setRequestProperty("Referer", referer)
            }

            val cookies = CookieManager.getInstance().getCookie(sourceUrl)
            if (!cookies.isNullOrBlank()) {
                setRequestProperty("Cookie", cookies)
            }
        }
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
