# CODEBASE.md

Developer reference for the ClothCall Android project.

## What this app is

ClothCall is an Android app for blind/visually impaired users. It photographs clothing, sends the image to Groq (Llama 4 Scout) for condition analysis, and delivers the AI result as a simulated incoming phone call — the trusted person's voice reads the report aloud via TTS, then listens for a spoken reply via STT.

## Build commands

```bash
# Debug build and install
./gradlew assembleDebug
./gradlew installDebug

# Signed release APK (requires local.properties signing keys — see below)
./gradlew assembleRelease

# Reset API key on device
adb shell pm clear com.clothcall
```

**Signing:** `local.properties` holds `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. These are read by `app/build.gradle.kts` and must never be committed.

**Groq API key:** `gsk_...` key from console.groq.com. Entered at first launch, stored in SharedPreferences.

## Architecture

- **Language:** Kotlin, Jetpack Compose (Material 3)
- **Navigation:** Navigation Compose — `AppNavigation.kt`
- **Database:** Room, version 2, `fallbackToDestructiveMigration`. Two tables: `garments`, `caregiver_profiles`
- **Network:** OkHttp + manual `org.json` — no Retrofit
- **AI:** Groq REST API, model `meta-llama/llama-4-scout-17b-16e-instruct`, called from `GeminiApiService.kt` (name kept for import compatibility — do not rename)
- **Camera:** CameraX `ImageCapture` + `ImageAnalysis` (live frame quality) + `Preview`
- **Voice out:** Android `TextToSpeech` — instantiated locally inside `CallUIScreen`, `HomeScreen`, and `CameraCapture`/`QuickScanScreen`. `AudioRouter` has its own TTS methods (`initTts`/`speak`/`shutdown`) that are **never called** — do not wire them. Each screen's TTS engine is stopped on `Lifecycle.Event.ON_PAUSE` via a `LifecycleEventObserver` (Compose stays alive when the Activity backgrounds, so speech must be stopped explicitly or it keeps talking after the user leaves).
- **Camera alignment:** `ALIGNMENT_PROMPT` (Groq, `classifyAlignment()`) classifies live frames into six non-directional states — `Good`, `Move closer`, `Move back`, `Recenter`, `No clothing found`, `Too dark`. Directional guidance ("Tilt up/down", "Turn left/right") was deliberately removed — a blind user cannot judge spatial orientation relative to an unseen frame, and the front-camera mirror flip made left/right answers backwards anyway.
- **Voice in:** Android `SpeechRecognizer`. Partial results are intentionally disabled to prevent acoustic feedback from the speaker triggering false commands.
- **Audio routing:** `AudioRouter.kt`. Home mode → `MODE_NORMAL` (TTS uses `STREAM_MUSIC`, routes to external speaker naturally). Out mode → `MODE_IN_CALL` (earpiece). `setCommunicationDevice()` must not be used — it forces `MODE_IN_COMMUNICATION` and routes to earpiece.
- **Telecom:** Self-managed `PhoneAccount` + `ConnectionService`. Out mode only — fires a real system incoming-call UI on the lock screen.

## Data flow: scan → call

```
HomeScreen
  LaunchedEffect(activeProfile): if shown profile not active in DB → setActiveProfile()
  User selects profile → viewModel.setActiveProfile(id) → DB updated
  User taps "Check My Clothes" → navigate QuickScanScreen

QuickScanScreen / CameraCapture
  VolumeCaptureBus.trigger registered via DisposableEffect(imageCapture)
  MainActivity.dispatchKeyEvent intercepts KEYCODE_VOLUME_DOWN → fires trigger
  StartupInstructionState (singleton, @Volatile spoken flag) → "Point your camera..." spoken once
  STARTUP_GUIDANCE_DELAY_MS (4.5s) grace window before guidance/auto-capture can start
  Cheap local averageBrightness() pre-filter → "Too dark" spoken without an API call
  Else → viewModel.classifyAlignment() (Groq, ALIGNMENT_PROMPT) → one of six guidance strings
    → TTS guidance spoken; "Good" enables auto-capture after AUTO_CAPTURE_HOLD_MS hold-still
  performCapture(): manual triggers (FAB / volume-down) always fire — only auto-capture is
    gated on captureEnabled (guidance == "Good")
  On capture → "Got it" spoken → ScanViewModel.analyze(bitmap)

ScanViewModel.analyze()
  reset() ScanResultHolder
  base64-encode bitmap (JPEG, max 1024px)
  getActiveProfile() ?: getFirstProfile() → caregiverName + fadeThreshold → ScanResultHolder
  loadBaselineBase64() → selectedGarmentId == -1 → always null (stain-only path)
  GeminiApiService.analyzeClothing() → ScanResultHolder.response
  ScanState.Done → navigate Route.CALL_UI

CallUIScreen / CallViewModel
  isOutMode=true  → TelecomHelper.startIncomingCall() → CallPhase.Ringing
  isOutMode=false → viewModel.startSpeaking() → CallPhase.Speaking (no ringing)
  Speaking → TTS reads ScanResultHolder.response → onTtsDone() → CallPhase.Listening
  900 ms delay before STT opens (speaker reverb dies down)
  handleVoiceCommand() priority order:
    "no" / "i'll change"          → Dismissed(warm=false)
    "more"/"detail"/"fading"/etc. → fetchMoreDetail() → requestMoreDetail() → Speaking
    "again"/"repeat"              → transitionToSpeaking()
    "already know"                → suppress finding → Dismissed(warm=true)
    "yes"/"okay"/"fine"/etc.      → Dismissed(warm=true)
    blank/unknown                 → retryListening() (increments listeningKey)
```

## ScanResultHolder

In-memory singleton bridging `ScanViewModel` → `CallViewModel`. Fields: `base64Image`, `baselineBase64`, `response`, `caregiverName`, `fadeThreshold`, `conversationHistory`, `reportedStains`. Reset at the start of each scan. Do not dependency-inject it — lightweight by design.

`conversationHistory` accumulates every assistant + user message turn. `requestMoreDetail()` replays the full list into the Groq request so multi-turn context is preserved across follow-ups.

## Groq prompt rules — do not loosen

Both `SYSTEM_PROMPT` (two-image, baseline compare) and `SINGLE_IMAGE_PROMPT` (one image, always active):
- Passive voice throughout — "a mark is visible", never "I can see"
- Precise location: "near the lower left cuff", "along the right collar edge"
- If a trusted person name + threshold is provided, **always use their actual name** — never "your trusted person" or any label. Lead with a short visual observation of the fading, then immediately follow with their opinion in the same sentence using one of three qualitative buckets (clearly below / approaching / at-or-beyond their threshold) — never speak the raw percentage. This combined description+framing pattern is intentionally moderate ("Lead with... then follow with...") rather than emphatic ("Always...Never...say both") — the stronger phrasing was tried and caused the trusted-person framing to bleed into unrelated follow-up answers (e.g. stain questions echoing fading opinions). Keep new prompt rules phrased moderately for the same reason.
- If no trusted person is provided, **do not mention one or invent a name**
- Low confidence → passive observation only ("lighting limits precision here") — never instruct the user to adjust position or retake
- Always ends with exactly: **Do you still want to wear it?**

These rules protect user autonomy and dignity.

## Trusted person (caregiver) profile

`CaregiverProfile` entity: `id`, `name`, `fadeThreshold` (Int, 0–30%), `isActive`.

- `CaregiverSetupScreen` calibrates threshold: five fabric images at 0/5/10/20/30% fading (rendered via `ColorFilter.colorMatrix`), rated Still fine / Borderline / Retire. `computeOverallThreshold()` returns the first Borderline/Retire level.
- `CaregiverViewModel.saveProfile()` auto-activates the newly created profile (clears all active flags first).
- `HomeScreen` `LaunchedEffect(activeProfile)` auto-activates the displayed profile if DB has no active flag set — keeps UI and DB in sync.
- `ScanViewModel.analyze()` uses `getActiveProfile() ?: getFirstProfile()` as fallback.

## Key quirks

- **`GeminiApiService`** calls Groq, not Gemini. `ClaudeApiService.kt` is a deprecated `typealias` — add no logic there.
- **Fading comparison** (two-image path with `SYSTEM_PROMPT`) is fully implemented but never triggered — `selectedGarmentId` is always -1 because there is no garment selector in the scan flow.
- **`listeningKey`** — `retryListening()` increments this instead of re-setting `CallPhase.Listening` (same-value `StateFlow` emissions are dropped). `CallUIScreen` keys its STT `LaunchedEffect` on `(phase, listeningKey)`.
- **`VolumeCaptureBus`** — `MainActivity.dispatchKeyEvent` (not `onKeyDown`) intercepts volume-down, which fires before any View including `PreviewView` can consume it. `CameraCapture` registers/clears the trigger via `DisposableEffect(imageCapture)`.
- **Home mode** has no ringing — `CallUIScreen` branches on `isOutMode`: Out → `viewModel.reset()` (Ringing phase), Home → `viewModel.startSpeaking()` (skips Ringing). Ringtone and vibration creation are also gated behind `if (isOutMode)`.
- **STT partial results** are disabled in `onPartialResults` — acoustic feedback from the speaker was triggering false commands before the user finished speaking.
- **`HomeScreen` TalkBack semantics** — every interactive element (`ProfileDropdown`, `ModeToggle` buttons, "Check My Clothes") uses `Modifier.clearAndSetSemantics { contentDescription = ...; role = Role.Button }` rather than plain `semantics {}`. Plain `semantics{}` merges descendant text nodes too, so TalkBack announced the visible label *and* the custom description back to back; `clearAndSetSemantics` replaces descendant semantics entirely.
- **`hasSpokenWelcome`** (`PreferencesManager` / `HomeViewModel`) — gates the HomeScreen welcome TTS ("Tap Check My Clothes to begin.") to speak once per app process. Reset to `false` in `ClothCallApplication.onCreate()`, so it fires on first HomeScreen visit after a fresh launch and never again until the process restarts — not on every navigation back to HomeScreen. Also suppressed entirely when `AccessibilityManager.isTouchExplorationEnabled()` is true, since TalkBack already announces focused elements.

## Versioning

`versionCode`/`versionName` live in `app/build.gradle.kts` `defaultConfig`. Bump `versionCode` (monotonic integer) on every release build that gets published; `versionName` is the human-facing string (e.g. `"1.1"`). Releases are published as GitHub Releases with the signed `app-release.apk` attached — tag format `vX.Y` (e.g. `v1.1`).
