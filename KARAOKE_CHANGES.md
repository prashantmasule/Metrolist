# Karaoke Mode — Change Documentation
> This file documents every modification made to Metrolist to implement Karaoke Mode.
> Use this as a reference when merging upstream Metrolist updates into this fork.
> Last updated: March 2026

---

## New Files Added (Zero Conflict Risk)

These files are 100% new — they will never conflict with upstream updates.

### `app/src/main/kotlin/com/metrolist/music/playback/KaraokeEngine.kt`
- Dual ExoPlayer manifold (instrumental + vocal players)
- Synchronized play/pause/seek across both players
- Vocal volume control (0.0 to 1.0)
- 5-second sync watchdog to correct drift
- `KaraokeState` enum: IDLE, LOADING, READY, ERROR

### `app/src/main/kotlin/com/metrolist/music/playback/KaraokeRepository.kt`
- Talks to the FastAPI backend
- Uploads cached audio file via multipart HTTP
- Downloads and caches stem files locally
- Cache location: `context.cacheDir/karaoke_stems/`
- Stem files named: `{songId}_vocals.mp3`, `{songId}_instrumental.mp3`
- Backend URL read from DataStore (`KaraokeBackendUrlKey`)

### `colab/server.py` (or saved in Google Drive)
- FastAPI + Demucs backend
- Runs on Google Colab GPU (T4)
- Model: `mdx_extra_q` (~15 sec processing)
- File hash caching to prevent duplicate jobs
- Endpoints: `/health`, `/split`, `/download/{job_id}/vocals`, `/download/{job_id}/instrumental`

### Hugging Face Space Files
- URL: `https://prashantmasule-metrolist-karaoke-api.hf.space`
- Model: `htdemucs` (fits in free tier RAM)
- Same API as Colab server
- Always-on fallback backend

---

## Modified Files (Conflict Risk — Check These on Every Merge)

---

### `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`

**What we added:**

1. Two lazy properties after `@Inject lateinit var database`:
```kotlin
// KARAOKE MOD START
val karaokeEngine: KaraokeEngine by lazy { KaraokeEngine(this) }
val karaokeRepository: KaraokeRepository by lazy { KaraokeRepository(this) }
val karaokeState = MutableStateFlow<String>("idle")
val karaokeVocalVolume = MutableStateFlow(1.0f)
val karaokePlayerMuted = MutableStateFlow(false)
private var karaokeJob: kotlinx.coroutines.Job? = null
private var prefetchJob: kotlinx.coroutines.Job? = null
// KARAOKE MOD END
```

2. In `onDestroy()`:
```kotlin
// KARAOKE MOD START
karaokeEngine.release()
prefetchJob?.cancel()
// KARAOKE MOD END
```

3. Three new functions added to the service body:
- `startKaraokeProcessing(songId, context)` — main karaoke pipeline
- `stopKaraoke()` — stops engine, fades player back in
- `fadePlayerVolume(from, to, durationMs)` — smooth volume transition

4. In `onMediaItemTransition` override — added prefetch trigger:
```kotlin
// KARAOKE MOD START
mediaItem?.mediaId?.let { songId -> startStemPrefetch(songId) }
// KARAOKE MOD END
```

**Conflict likelihood:** HIGH — MusicService.kt is frequently updated by author.
**Resolution strategy:** Keep all karaoke functions intact, only integrate new upstream code around them.

---

### `app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt`

**What we added:**

After `val isMuted = service.isMuted` (~line 175):
```kotlin
// KARAOKE MOD START
val karaokeEngine get() = service.karaokeEngine
val karaokeRepository get() = service.karaokeRepository
// KARAOKE MOD END
```

**Conflict likelihood:** LOW — small addition in a stable section.

---

### `app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt`

**What we added:**

1. Karaoke state variables (after sleep timer variables, ~line 559):
```kotlin
// KARAOKE MOD START
val karaokeServiceState by playerConnection.service.karaokeState.collectAsState()
var isKaraokeActive by remember {
    mutableStateOf(playerConnection.service.karaokeState.value != "idle")
}
var vocalVolume by remember { mutableFloatStateOf(playerConnection.service.karaokeVocalVolume.value) }
val karaokeStemsReady by remember { derivedStateOf { karaokeServiceState == "ready" } }
val karaokeIsProcessing by remember { derivedStateOf { karaokeServiceState == "processing" } }
val karaokePlayerMuted by playerConnection.service.karaokePlayerMuted.collectAsState()
// KARAOKE MOD END
```

2. Three LaunchedEffect blocks for karaoke wiring (directly after state variables):
- `LaunchedEffect(karaokeServiceState)` — reacts to service state changes
- `LaunchedEffect(isKaraokeActive, mediaMetadata?.id)` — triggers processing
- `LaunchedEffect(vocalVolume)` — wires slider to engine
- `LaunchedEffect(isPlayingState)` — syncs play/pause with engine
- `LaunchedEffect(karaokePlayerMuted)` — prevents audio blip on recomposition

3. Updated `LyricsMenu` call to pass karaoke parameters

**Conflict likelihood:** HIGH — Player.kt is a large, frequently modified file.
**Resolution strategy:** The karaoke state variables and LaunchedEffects are self-contained blocks. Keep them together as a unit when resolving conflicts.

---

### `app/src/main/kotlin/com/metrolist/music/ui/menu/LyricsMenu.kt`

**What we added:**

1. New parameters to `LyricsMenu` composable signature:
```kotlin
// KARAOKE MOD START
isKaraokeActive: Boolean = false,
karaokeIsProcessing: Boolean = false,
karaokeStemsReady: Boolean = false,
vocalVolume: Float = 1.0f,
onKaraokeToggle: () -> Unit = {},
onVocalVolumeChange: (Float) -> Unit = {},
// KARAOKE MOD END
```

2. New `Material3MenuItemData` item for karaoke toggle with inline vocal slider
   - Added inside the last `Material3MenuGroup` buildList
   - Positioned before the "Romanize current track" item

**Conflict likelihood:** MEDIUM — LyricsMenu is occasionally updated.

---

### `app/src/main/kotlin/com/metrolist/music/ui/screens/settings/ContentSettings.kt`

**What we added:**

1. Import:
```kotlin
import com.metrolist.music.constants.KaraokeBackendUrlKey
```

2. State variable:
```kotlin
// KARAOKE MOD START
val (karaokeBackendUrl, onKaraokeBackendUrlChange) = rememberPreference(
    KaraokeBackendUrlKey,
    defaultValue = "https://prashantmasule-metrolist-karaoke-api.hf.space"
)
var showKaraokeUrlDialog by rememberSaveable { mutableStateOf(false) }
// KARAOKE MOD END
```

3. Karaoke URL AlertDialog (before proxy dialog)

4. New `Material3SettingsGroup` for Karaoke (before the "Misc" group)

**Conflict likelihood:** MEDIUM

---

### `app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt`

**What we added:**

After `ProxyPasswordKey`:
```kotlin
// KARAOKE MOD START
val KaraokeBackendUrlKey = stringPreferencesKey("karaokeBackendUrl")
// KARAOKE MOD END
```

**Conflict likelihood:** LOW — just one line addition.

---

### `app/src/main/res/values/metrolist_strings.xml`

**What we added:**

```xml
<!-- KARAOKE MOD START -->
<string name="karaoke_mode">Karaoke Mode</string>
<string name="karaoke_processing">Processing…</string>
<string name="karaoke_vocal_level">Vocal Level</string>
<string name="karaoke_backend_url">Karaoke Backend URL</string>
<!-- KARAOKE MOD END -->
```

**Conflict likelihood:** LOW — strings are append-only.

---

### `app/src/main/res/drawable/mic.xml`

- Already existed in the project — no changes made.

---

### `app/build.gradle.kts`

**What we changed:**

Simplified debug signing config to bypass corrupted keystore:
```kotlin
// KARAOKE MOD: Simplified from original 3-condition if/else
signingConfig =
    if (workflowDebugKeystoreFile != null) {
        signingConfigs.getByName("workflowDebug")
    } else {
        signingConfigs.getByName("debug")
    }
```

**Conflict likelihood:** LOW — but check on every merge since author may update signing config.

---

## Backend Architecture

```
Android App
    ↓ (multipart upload, ~1-2MB audio)
FastAPI Server (HF Space or Colab)
    ↓
Demucs ML Model (htdemucs on HF, mdx_extra_q on Colab)
    ↓
Two stem files: vocals.mp3 + no_vocals.mp3
    ↓ (downloaded to device)
KaraokeEngine (dual ExoPlayer)
    ↓
Mixed audio output (vocal volume controlled by slider)
```

---

## Settings

- **Backend URL:** Settings → Content → Karaoke Backend URL
- **Default:** `https://prashantmasule-metrolist-karaoke-api.hf.space` (HF Space)
- **Fast option:** Colab ngrok URL (update when Colab session starts)

---

## Known Bugs / Pending Improvements

1. Audio blip on UI recomposition (orientation change, app reopen)
2. Track switching doesn't immediately cancel in-progress upload
3. Duplicate upload retries when server takes long to respond
4. Large file upload can cause connection abort on slow networks
5. Background pre-fetching not yet implemented
6. Colab URL changes every session (use ngrok static domain to fix)
7. HF Space hibernates if unused — first request after hibernation is slow

---

## How To Merge Upstream Updates

```bash
# 1. Add upstream remote (one time only)
git remote add upstream https://github.com/MetrolistGroup/Metrolist.git

# 2. Fetch latest upstream
git fetch upstream

# 3. Switch to your branch
git checkout feature/karaoke-mode

# 4. Merge upstream
git merge upstream/main

# 5. Resolve conflicts (use this file as reference)
# For each conflicted file, keep BOTH:
# - The upstream author's new code
# - Your karaoke code marked with // KARAOKE MOD comments

# 6. Build and test
# Trigger GitHub Actions build
# Test karaoke mode on device
```

---

## Contact / Repository

- Fork: `https://github.com/prashantmasule/Metrolist`
- Branch: `feature/karaoke-mode`
- HF Space: `https://prashantmasule-metrolist-karaoke-api.hf.space`
- Feature built: March 2026
