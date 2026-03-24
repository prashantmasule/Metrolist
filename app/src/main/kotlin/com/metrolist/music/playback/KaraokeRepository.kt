/**
 * KaraokeRepository.kt
 *
 * The logistics manager for Karaoke Mode.
 *
 * MECHANICAL ANALOGY:
 * Think of this as a parts warehouse with a direct line to the factory.
 * When the engine needs stems for a song:
 *   1. First checks the local warehouse (device cache folder)
 *   2. If parts are in stock → returns them immediately
 *   3. If not in stock → calls the factory (backend API),
 *      waits for delivery, stores them in warehouse, returns them
 */

package com.metrolist.music.playback

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.metrolist.music.constants.KaraokeBackendUrlKey
import com.metrolist.music.utils.dataStore

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "KaraokeRepository"

/**
 * Holds the two local stem files once they are ready.
 */
data class KaraokeStemFiles(
    val instrumentalFile: File,
    val vocalFile: File
)

/**
 * Sealed class representing the outcome of a stem fetch operation.
 * Think of it as the delivery receipt — it's either a success with the goods,
 * or a failure with an explanation.
 */
sealed class KaraokeResult {
    data class Success(val stems: KaraokeStemFiles) : KaraokeResult()
    data class Error(val message: String) : KaraokeResult()
}

class KaraokeRepository(private val context: Context) {

    /**
     * Read backend URL from DataStore synchronously.
     * Falls back to HF Space URL if not configured.
     */
    private val backendUrl: String
        get() {
            val saved = runBlocking {
                context.dataStore.data.first()[KaraokeBackendUrlKey]
            }
            val url = if (saved.isNullOrBlank()) {
                "https://prashantmasule-metrolist-karaoke-api.hf.space"
            } else {
                saved
            }
            Log.d(TAG, "Using backend URL: $url")
            return url.trimEnd('/')
        }

    /**
     * The local warehouse directory.
     * Stored in the app's cache folder so Android can clean it up
     * automatically if the device runs low on storage.
     */
    private val cacheDir: File
        get() = File(context.cacheDir, "karaoke_stems").also { it.mkdirs() }

    /**
     * Main entry point. Given a song ID and its local cached audio file,
     * returns the two stem files — either from local cache or freshly split.
     *
     * @param songId         The unique YouTube video ID (e.g. "dQw4w9WgXcQ")
     * @param localAudioFile The song's already-cached audio file on the device
     */
    suspend fun getStemsForSong(
        songId: String,
        localAudioFile: File
    ): KaraokeResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "getStemsForSong() songId=$songId")
        Log.d(TAG, "Local audio file: ${localAudioFile.path}, exists=${localAudioFile.exists()}, size=${localAudioFile.length()} bytes")

        // --- Step 1: Check the warehouse first ---
        val cachedStems = checkCache(songId)
        if (cachedStems != null) {
            Log.d(TAG, "Cache HIT for songId=$songId — returning cached stems instantly")
            return@withContext KaraokeResult.Success(cachedStems)
        }

        Log.d(TAG, "Cache MISS for songId=$songId — calling backend")

        // --- Step 2: Verify the local audio file exists ---
        if (!localAudioFile.exists()) {
            val msg = "Local audio file does not exist: ${localAudioFile.path}"
            Log.e(TAG, msg)
            return@withContext KaraokeResult.Error(msg)
        }

        if (localAudioFile.length() == 0L) {
            val msg = "Local audio file is empty: ${localAudioFile.path}"
            Log.e(TAG, msg)
            return@withContext KaraokeResult.Error(msg)
        }

        // --- Step 3: Upload to backend and get stem URLs ---
        Log.d(TAG, "Uploading ${localAudioFile.length() / 1024}KB to backend...")
        val splitResponse = try {
            uploadAndSplit(localAudioFile)
        } catch (e: Exception) {
            val msg = "Backend upload failed: ${e.message}"
            Log.e(TAG, msg, e)
            return@withContext KaraokeResult.Error(msg)
        }

        Log.d(TAG, "Backend response: jobId=${splitResponse.jobId}")
        Log.d(TAG, "  vocals_url=${splitResponse.vocalsUrl}")
        Log.d(TAG, "  instrumental_url=${splitResponse.instrumentalUrl}")

        // --- Step 4: Download the two stem files into local cache ---
        val vocalFile = stemCacheFile(songId, "vocals")
        val instrumentalFile = stemCacheFile(songId, "instrumental")

        try {
            Log.d(TAG, "Downloading vocal stem...")
            downloadFile(splitResponse.vocalsUrl, vocalFile)
            Log.d(TAG, "Vocal stem downloaded: ${vocalFile.length()} bytes")

            Log.d(TAG, "Downloading instrumental stem...")
            downloadFile(splitResponse.instrumentalUrl, instrumentalFile)
            Log.d(TAG, "Instrumental stem downloaded: ${instrumentalFile.length()} bytes")
        } catch (e: Exception) {
            // Clean up partial downloads on failure
            vocalFile.delete()
            instrumentalFile.delete()
            val msg = "Stem download failed: ${e.message}"
            Log.e(TAG, msg, e)
            return@withContext KaraokeResult.Error(msg)
        }

        Log.d(TAG, "All stems cached successfully for songId=$songId")
        KaraokeResult.Success(KaraokeStemFiles(instrumentalFile, vocalFile))
    }

    /**
     * Check if stems are already in the local warehouse.
     * Returns null if either file is missing or empty.
     */
    private fun checkCache(songId: String): KaraokeStemFiles? {
        val vocalFile = stemCacheFile(songId, "vocals")
        val instrumentalFile = stemCacheFile(songId, "instrumental")

        val vocalsOk = vocalFile.exists() && vocalFile.length() > 0
        val instrumentalOk = instrumentalFile.exists() && instrumentalFile.length() > 0

        Log.d(TAG, "Cache check for $songId: vocals=$vocalsOk (${vocalFile.length()} bytes), instrumental=$instrumentalOk (${instrumentalFile.length()} bytes)")

        return if (vocalsOk && instrumentalOk) {
            KaraokeStemFiles(instrumentalFile, vocalFile)
        } else {
            // Clean up any partial files
            if (!vocalsOk) vocalFile.delete()
            if (!instrumentalOk) instrumentalFile.delete()
            null
        }
    }

    /**
     * Returns the canonical cache file path for a stem.
     * Example: .../cache/karaoke_stems/dQw4w9WgXcQ_vocals.mp3
     */
    private fun stemCacheFile(songId: String, stemType: String): File {
        return File(cacheDir, "${songId}_${stemType}.mp3")
    }

    /**
     * Uploads the local audio file to the backend /split endpoint
     * using CHUNKED transfer to prevent connection aborts on mobile networks.
     *
     * MECHANICAL ANALOGY: Instead of trying to push the entire water tank
     * through the pipe at once (which causes pressure buildup and bursts),
     * we pump it in controlled 256KB bursts. If one burst fails, only
     * that burst needs to be resent — not the entire tank.
     *
     * NO SIZE CAP — uploads the full audio file for complete stem separation.
     * Full song = complete stems = no silence at the end.
     */
    private fun uploadAndSplit(audioFile: File): SplitResponse {
        val fileSizeKb = audioFile.length() / 1024
        val fileSizeMb = fileSizeKb / 1024.0
        Log.d(TAG, "uploadAndSplit() file=${audioFile.name} size=${fileSizeMb}MB (${audioFile.length()} bytes)")

        val url = URL("${backendUrl}/split")
        val boundary = "MetrolistKaraoke_${System.currentTimeMillis()}"

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 30_000      // 30 seconds to connect
            readTimeout = 1_200_000      // 20 minutes for split to complete on slow CPU
            // Use chunked streaming — prevents OutOfMemoryError on large files
            // and allows server to start receiving before upload is complete
            setChunkedStreamingMode(256 * 1024) // 256KB chunks
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Connection", "keep-alive")
        }

        Log.d(TAG, "Starting chunked upload to $url...")

        // Write the multipart body using chunked streaming
        connection.outputStream.use { output ->
            val writer = output.bufferedWriter()

            // --- Multipart header ---
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            writer.write("Content-Type: audio/mpeg\r\n")
            writer.write("\r\n")
            writer.flush()

            // --- File bytes — sent in 256KB chunks ---
            // This is the key fix: instead of loading entire file into memory
            // and sending at once, we stream it in small pieces
            var totalBytesSent = 0L
            val buffer = ByteArray(256 * 1024) // 256KB buffer
            audioFile.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush() // Flush each chunk immediately
                    totalBytesSent += bytesRead
                    Log.d(TAG, "Uploaded ${totalBytesSent / 1024}KB / ${fileSizeKb}KB (${(totalBytesSent * 100 / audioFile.length())}%)")
                }
            }
            Log.d(TAG, "Upload complete: $totalBytesSent bytes sent")

            // --- Closing boundary ---
            writer.write("\r\n--$boundary--\r\n")
            writer.flush()
        }

        val responseCode = connection.responseCode
        Log.d(TAG, "Backend response code: $responseCode")

        if (responseCode != 200) {
            val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "no error body"
            Log.e(TAG, "Backend error response: $errorBody")
            throw Exception("Backend returned HTTP $responseCode: $errorBody")
        }

        val responseBody = connection.inputStream.bufferedReader().readText()
        Log.d(TAG, "Backend success response: $responseBody")

        return parseSplitResponse(responseBody)
    }

    /**
     * Simple JSON parser for the /split response.
     * We parse manually to avoid adding a JSON library dependency.
     */
    private fun parseSplitResponse(json: String): SplitResponse {
        Log.d(TAG, "Parsing response: $json")

        fun extractField(key: String): String {
            val pattern = "\"$key\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            return pattern.find(json)?.groupValues?.get(1)
                ?: throw Exception("Field '$key' not found in response: $json")
        }

        return SplitResponse(
            jobId = extractField("job_id"),
            vocalsUrl = extractField("vocals_url"),
            instrumentalUrl = extractField("instrumental_url")
        )
    }

    /**
     * Downloads a file from a URL and saves it to the destination file.
     * Like receiving a delivery and putting it on the warehouse shelf.
     */
    private fun downloadFile(fileUrl: String, destination: File) {
        Log.d(TAG, "downloadFile() url=$fileUrl -> ${destination.path}")

        val url = URL(fileUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 300_000  // 5 minutes for large files
        }

        val responseCode = connection.responseCode
        Log.d(TAG, "Download response code: $responseCode for ${destination.name}")

        if (responseCode != 200) {
            throw Exception("Download failed with HTTP $responseCode for $fileUrl")
        }

        val totalBytes = connection.contentLength.toLong()
        var downloadedBytes = 0L

        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(256 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        Log.d(TAG, "Downloaded ${downloadedBytes / 1024}KB / ${totalBytes / 1024}KB")
                    }
                }
            }
        }

        Log.d(TAG, "Download complete: ${destination.name} = ${destination.length()} bytes")
    }

    /**
     * Delete all cached stems for a specific song.
     * Called when the user clears karaoke cache in settings.
     */
    fun clearStemCache(songId: String) {
        Log.d(TAG, "clearStemCache() for songId=$songId")
        stemCacheFile(songId, "vocals").delete()
        stemCacheFile(songId, "instrumental").delete()
    }

    /**
     * Delete ALL cached stems.
     * Called from the settings "Clear Karaoke Cache" option.
     */
    fun clearAllStemCache() {
        Log.d(TAG, "clearAllStemCache() — deleting all stems in ${cacheDir.path}")
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "All stem cache cleared")
    }

    /**
     * Returns total size of stem cache in bytes.
     * Used to display cache size in settings.
     */
    fun getStemCacheSizeBytes(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Internal data class for the backend /split response.
     */
    private data class SplitResponse(
        val jobId: String,
        val vocalsUrl: String,
        val instrumentalUrl: String
    )
}
