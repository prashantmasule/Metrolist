/**
 * KaraokeEngine.kt
 * 
 * The dual-player manifold for Karaoke Mode.
 *
 * MECHANICAL ANALOGY:
 * Imagine two water pipes running in parallel, perfectly synchronized:
 *   - Pipe A (instrumentalPlayer): always fully open, fixed flow rate
 *   - Pipe B (vocalPlayer): has a valve (vocalVolume 0.0 to 1.0)
 * Both pipes are driven by the same pump (synchronized play/pause/seek).
 * The user controls Pipe B's valve via the UI slider.
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "KaraokeEngine"

/**
 * Represents the current state of the KaraokeEngine.
 * Think of these as the status lights on the control panel.
 */
enum class KaraokeState {
    IDLE,        // Engine is off, not loaded
    LOADING,     // Stems are being prepared (files loading into players)
    READY,       // Both players loaded and synchronized, ready to play
    ERROR        // Something went wrong
}

class KaraokeEngine(private val context: Context) {

    // --- The Two Players (The Two Pipes) ---
    private var instrumentalPlayer: ExoPlayer? = null  // Pipe A: always full volume
    private var vocalPlayer: ExoPlayer? = null          // Pipe B: user-controlled valve

    // --- State ---
    var state: KaraokeState = KaraokeState.IDLE
        private set

    // The vocal volume (0.0 = fully muted = pure instrumental, 1.0 = full vocals)
    // Think of this as the valve position on Pipe B
    var vocalVolume: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            vocalPlayer?.volume = field
            Log.d(TAG, "Vocal valve set to: $field")
        }

    // Callback to notify the UI when state changes
    var onStateChanged: ((KaraokeState) -> Unit)? = null

    // --- Sync watchdog ---
    // This is a periodic quality-control inspector that checks every 5 seconds
    // if both pipes are flowing at the same rate (position in sync)
    private val engineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var syncWatchdogJob: Job? = null

    /**
     * Load the two stem files and prepare both players.
     *
     * @param instrumentalFile  The cached instrumental .mp3 file on device storage
     * @param vocalFile         The cached vocals .mp3 file on device storage
     * @param startPositionMs   Where to start playback (to sync with the main player)
     */
    fun load(
        instrumentalFile: File,
        vocalFile: File,
        startPositionMs: Long = 0L
    ) {
        Log.d(TAG, "load() called. instrumental=${instrumentalFile.path}, vocal=${vocalFile.path}")
        Log.d(TAG, "Files exist: instrumental=${instrumentalFile.exists()}, vocal=${vocalFile.exists()}")

        setState(KaraokeState.LOADING)

        // Release any previously loaded players first
        releasePlayersInternal()

        // Build Player A — Instrumental (the always-open pipe)
        instrumentalPlayer = ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(Uri.fromFile(instrumentalFile))
            setMediaItem(item)
            volume = 1.0f  // Always fully open
            prepare()
            seekTo(startPositionMs)
            Log.d(TAG, "Instrumental player prepared at position ${startPositionMs}ms")
        }

        // Build Player B — Vocal (the valve-controlled pipe)
        vocalPlayer = ExoPlayer.Builder(context).build().apply {
            val item = MediaItem.fromUri(Uri.fromFile(vocalFile))
            setMediaItem(item)
            volume = vocalVolume  // Apply current valve setting
            prepare()
            seekTo(startPositionMs)
            Log.d(TAG, "Vocal player prepared at position ${startPositionMs}ms")
        }

        // Add a listener to know when both players are ready
        // We use the instrumental as the "master" clock
        instrumentalPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "Instrumental player state changed: $playbackState")
                if (playbackState == Player.STATE_READY) {
                    Log.d(TAG, "KaraokeEngine is READY")
                    setState(KaraokeState.READY)
                } else if (playbackState == Player.STATE_IDLE) {
                    Log.d(TAG, "KaraokeEngine went IDLE unexpectedly")
                    setState(KaraokeState.ERROR)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Instrumental player error: ${error.message}")
                setState(KaraokeState.ERROR)
            }
        })

        vocalPlayer?.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Vocal player error: ${error.message}")
                // Don't kill karaoke mode entirely — just mute the vocal pipe
                vocalPlayer?.volume = 0f
            }
        })
    }

    /**
     * Start both players simultaneously.
     * Like opening the main pump valve — both pipes flow at once.
     */
    fun play() {
        Log.d(TAG, "play() called. State=$state")
        instrumentalPlayer?.play()
        vocalPlayer?.play()
        startSyncWatchdog()
    }

    /**
     * Pause both players simultaneously.
     * Like stopping the pump — both pipes stop instantly.
     */
    fun pause() {
        Log.d(TAG, "pause() called")
        instrumentalPlayer?.pause()
        vocalPlayer?.pause()
        stopSyncWatchdog()
    }

    /**
     * Seek both players to the same position.
     * Like resetting both flowmeters to the same reading.
     *
     * @param positionMs  Target position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        Log.d(TAG, "seekTo(${positionMs}ms) called")
        instrumentalPlayer?.seekTo(positionMs)
        vocalPlayer?.seekTo(positionMs)
    }

    /**
     * Get the current playback position (from the master instrumental player).
     */
    fun getCurrentPosition(): Long {
        return instrumentalPlayer?.currentPosition ?: 0L
    }

    /**
     * The sync watchdog.
     *
     * MECHANICAL ANALOGY: Imagine two conveyor belts that must stay in sync.
     * This is an inspector who checks every 5 seconds that both belts
     * are at the same position. If one has drifted more than 50ms,
     * it snaps the slower one forward to match.
     */
    private fun startSyncWatchdog() {
        stopSyncWatchdog()
        syncWatchdogJob = engineScope.launch {
            while (isActive) {
                delay(5000L) // Check every 5 seconds

                val instPos = instrumentalPlayer?.currentPosition ?: continue
                val vocalPos = vocalPlayer?.currentPosition ?: continue
                val drift = Math.abs(instPos - vocalPos)

                Log.d(TAG, "Sync check: instrumental=${instPos}ms, vocal=${vocalPos}ms, drift=${drift}ms")

                if (drift > 50) {
                    // Drift detected — snap the vocal pipe to match the instrumental master
                    Log.w(TAG, "DRIFT DETECTED: ${drift}ms — re-syncing vocal player to ${instPos}ms")
                    vocalPlayer?.seekTo(instPos)
                }
            }
        }
    }

    private fun stopSyncWatchdog() {
        syncWatchdogJob?.cancel()
        syncWatchdogJob = null
    }

    private fun setState(newState: KaraokeState) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    private fun releasePlayersInternal() {
        Log.d(TAG, "Releasing internal players")
        instrumentalPlayer?.release()
        instrumentalPlayer = null
        vocalPlayer?.release()
        vocalPlayer = null
    }

    /**
     * Fully shut down the engine and release all resources.
     * Call this when karaoke mode is turned off.
     * Like shutting down the entire manifold and draining the pipes.
     */
    fun release() {
        Log.d(TAG, "release() called — shutting down KaraokeEngine")
        stopSyncWatchdog()
        releasePlayersInternal()
        engineScope.cancel()
        setState(KaraokeState.IDLE)
    }
}
