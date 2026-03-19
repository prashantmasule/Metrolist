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

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "KaraokeRepository"

// ⚠️ IMPORTANT: Replace this with your actual Hugging Face Space URL
// Example: "https://prashantmasule-metrolist-karaoke-api.hf.space"
private const val BACKEND_BASE_URL = "https://prashantmasule-metrolist-karaoke-api.hf.space"

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
     * @param songId        The unique YouTube video ID (e.g. "dQw4w9WgXcQ")
     * @param localAudioFile The song's already-cached audio file on the device
     */
    suspend fun getStemsForSong(
        songId: String,
        localAudioFile: File
    ): KaraokeResult = withContext(Dispatchers.IO) {

        Log.d(TAG, "getStemsForSong() songId=$songId")
        Log.d(TAG, "Local audio file: ${localAudioFile.path}, exists=${localAudioFile.exists()}")

        // --- Step 1: Check the warehouse first ---
        val cachedStems = checkCache(songId)
        if (cachedStems != null) {
            Log.d(TAG, "Cache HIT for songId=$songId — returning cached stems")
            return@withContext KaraokeResult.Success(cachedStems)
        }

        Log.d(TAG, "Cache MISS for songId=$songId — calling backend")

        // --- Step 2: Verify the local audio file exists ---
        if (!localAudioFile.exists()) {
            val msg = "Local audio file does not exist: ${localAudioFile.path}"
            Log.e(TAG, msg)
            return@withContext KaraokeResult.Error(msg)
        }

        // --- Step 3: Upload to backend and get stem URLs ---
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

        Log.d(TAG, "Cache check for $songId: vocals=$vocalsOk, instrumental=$instrumentalOk")

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
     * and returns the parsed response containing download URLs.
     */
    private fun uploadAndSplit(audioFile: File): SplitResponse {
        Log.d(TAG, "uploadAndSplit() file=${audioFile.name} size=${audioFile.length()} bytes")

        val url = URL("$BACKEND_BASE_URL/split")
        val boundary = "MetrolistKaraoke_${System.currentTimeMillis()}"

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 30_000   // 30 seconds to connect
            readTimeout = 600_000     // 10 minutes for the split to complete
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }

        // Write the multipart body
        // Think of this like packing the audio file into a shipping container
        connection.outputStream.use { output ->
            val writer = output.bufferedWriter()

            // --- Part header ---
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n")
            writer.write("Content-Type: audio/mpeg\r\n")
            writer.write("\r\n")
            writer.flush()

            // --- File bytes ---
            audioFile.inputStream().use { input ->
                input.copyTo(output)
            }
            output.flush()

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
     * We parse manually to avoid adding a JSON library dependency —
     * the response format is simple and predictable.
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

        connection.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
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
