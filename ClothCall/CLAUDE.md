"ClothCall uses Llama 4 Scout, Meta's open source multimodal model, running on Groq's inference infrastructure. Llama 4 Scout is a vision-language model — it can receive both images and text as input and reason about what it sees. We send it the clothing photo along with a carefully designed prompt that instructs it to describe condition in passive voice, reference the caregiver by name, and calibrate its judgment to their personal tolerance threshold."

# ClothCall

Android app that checks clothing condition before the user wears it. A photo is taken, sent to Groq (LLaMA 4 Scout), and the AI response is delivered as a simulated incoming phone call from a named "trusted person" (caregiver).

## What it does

1. User opens the app and taps **Check My Clothes**.
2. Camera opens → user takes a photo of what they're about to wear.
3. The photo (plus optional baseline) is sent to Groq with a system prompt tuned for clothing condition.
4. Result arrives as a simulated incoming call: ringtone + vibration, answer/decline buttons.
5. On answer, TTS reads the AI response aloud.
6. User responds by voice: **yes / no / repeat / more detail / already know**.
7. "More detail" fires a multi-turn follow-up to Groq with the same image(s).

**Home/Out toggle** on the home screen switches audio routing:
- *Home* — in-app speakerphone (`MODE_NORMAL`).
- *Out* — uses Android Telecom self-managed calls; audio routes through the earpiece (`MODE_IN_CALL`) and the system lock screen shows an incoming call UI.

## Scan mode

Currently only **stain-only mode** is active. A single image is sent using `STAIN_ONLY_PROMPT` as the system prompt. The app looks for stains, marks, discoloration, and damage.

The fading + stain comparison mode (two images: wardrobe baseline vs. today's scan) exists in the codebase (`ScanViewModel.loadBaselineBase64`, `GeminiApiService.analyzeClothing` with `baselineBase64 != null`, `SYSTEM_PROMPT`) but is not reachable from the UI because there is no garment selector on the HomeScreen. `PreferencesManager.selectedGarmentId` defaults to -1 and is never updated, so `loadBaselineBase64()` always returns null and the stain-only path always runs.

## Architecture

- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Navigation:** Navigation Compose (`AppNavigation.kt`)
- **Database:** Room (single `AppDatabase` with two tables, version 2)
- **Network:** OkHttp (no Retrofit), direct JSON construction with `org.json`
- **AI:** Groq REST API — model `meta-llama/llama-4-scout-17b-16e-instruct` (`GeminiApiService.kt` — class name kept for import compatibility)
- **Telecom:** Android Telecom self-managed `PhoneAccount` + `ConnectionService`
- **Camera:** CameraX `ImageCapture`
- **Voice in:** `SpeechRecognizer` (Android STT)
- **Voice out:** `TextToSpeech` (Android TTS) — managed locally in `CallUIScreen`, not through `AudioRouter`
- **Audio routing:** `AudioRouter.kt` — called by `CallViewModel` on phase transitions

## Project layout

```
app/src/main/java/com/clothcall/
├── api/
│   ├── GeminiApiService.kt        # Groq REST calls: analyzeClothing + requestMoreDetail
│   └── ClaudeApiService.kt        # deprecated typealias → GeminiApiService (do not touch)
├── data/db/
│   ├── AppDatabase.kt             # Room singleton, version 2, fallbackToDestructiveMigration
│   ├── Garment.kt / GarmentDao.kt
│   └── CaregiverProfile.kt / CaregiverProfileDao.kt
├── telecom/
│   ├── TelecomHelper.kt           # register account, start/end system calls
│   └── ClothCallConnectionService.kt
├── ui/
│   ├── navigation/AppNavigation.kt
│   ├── screens/
│   │   ├── HomeScreen.kt          # profile dropdown, Home/Out toggle, "Check My Clothes" button
│   │   ├── ApiKeySetupScreen.kt   # Groq API key entry
│   │   ├── QuickScanScreen.kt     # camera capture → ScanViewModel.analyze()
│   │   ├── CallUIScreen.kt        # ringing → speaking → listening → dismissed
│   │   ├── CaregiverSetupScreen.kt
│   │   ├── WardrobeScreen.kt
│   │   └── SharedComposables.kt   # LoadingOverlay, ErrorOverlay, PermissionDeniedScreen
│   ├── viewmodels/
│   │   ├── ScanViewModel.kt       # image → base64 → baseline load → Groq → ScanResultHolder
│   │   ├── CallViewModel.kt       # CallPhase state machine + AudioRouter + STT retry key
│   │   ├── HomeViewModel.kt       # profiles + garments + selectedGarmentId (garments unused in UI)
│   │   ├── WardrobeViewModel.kt
│   │   └── CaregiverViewModel.kt
│   └── theme/
│       ├── Color.kt / Theme.kt / Type.kt
└── utils/
    ├── PreferencesManager.kt      # SharedPreferences: apiKey, isOutMode, selectedGarmentId
    ├── ScanResultHolder.kt        # in-memory singleton bridge scan→call
    └── AudioRouter.kt             # AudioManager mode switching + own TTS engine (initTts/speak/shutdown)
```

## Data flow: scan to call

```
HomeScreen
  → user selects profile from dropdown (sets active profile in DB)
  → user taps "Check My Clothes"

QuickScanScreen
  → imageProxyToBitmap()
  → ScanViewModel.analyze(bitmap)
      → bitmap compressed to JPEG, base64-encoded → ScanResultHolder.base64Image
      → loadBaselineBase64()
          → prefs.selectedGarmentId is always -1 (no UI to set it)
              → always returns null → stain-only path runs
      → caregiverDao.getActiveProfile() → caregiverName, fadeThreshold
      → GeminiApiService.analyzeClothing(base64Image, null, caregiverName, fadeThreshold)
          → baselineBase64 == null → userMessageWithImage + STAIN_ONLY_PROMPT
      → ScanResultHolder.response = text → ScanState.Done
  → navigate to Route.CALL_UI
      (isOutMode=true → TelecomHelper.startIncomingCall() first)

CallUIScreen
  → CallViewModel.reset()              → CallPhase.Ringing
  → user taps Answer → .answer()
      → AudioRouter.routeToEarpiece()  (Out) or .routeToSpeaker() (Home)
      → CallPhase.Speaking (after 2 s delay)
  → TTS (local TextToSpeech in CallUIScreen) reads ScanResultHolder.response
  → TTS done → .onTtsDone()           → CallPhase.Listening
  → LaunchedEffect(phase, listeningKey) → startListening()
  → STT result → .handleVoiceCommand()
      "yes"/"i'll wear"/"wear it"  → CallPhase.Dismissed(warm=true)
      "no"/"change"/"i'll change"  → CallPhase.Dismissed(warm=false)
      "repeat"/"again"             → transitionToSpeaking()
      "more"/"detail"/"fade"/etc.  → fetchMoreDetail()
                                      → GeminiApiService.requestMoreDetail(...)
                                      → transitionToSpeaking() with new text
      "already"/"already know"     → stores first sentence in reportedStains → Dismissed(warm=true)
      blank/unknown                → retryListening() → listeningKey++ → STT restarts
  → Dismissed → TTS "Enjoy your day." (warm=true) → navigate Home
  → onCleared → AudioRouter.resetRouting()
```

## Key design decisions

### ScanResultHolder singleton
`ScanResultHolder` is an in-memory `object` bridging `ScanViewModel` and `CallViewModel`. Fields: `base64Image`, `baselineBase64`, `response`, `caregiverName`, `fadeThreshold`, `conversationHistory`, `reportedStains`. Reset at the start of each new scan via `reset()`. Do not make it dependency-injected — the current design is intentionally lightweight.

### caregiverName in CallViewModel
`CallViewModel.caregiverName` is hardcoded to `"ClothCall"` — it does **not** read from `ScanResultHolder.caregiverName`. The ringing screen and in-call screen always display "ClothCall" as the caller name, regardless of the active caregiver profile.

### Baseline image flow
`prefs.selectedGarmentId` (Int, default -1) is intended to store which wardrobe item is being worn. There is currently no UI that sets this value; it stays at -1 across all sessions. `ScanViewModel.loadBaselineBase64()` returns null whenever the id is -1, keeping the app permanently in stain-only mode. The baseline comparison code path is complete and correct — it is simply never triggered.

### STT retry — listeningKey
`CallViewModel` holds `_listeningKey: MutableStateFlow<Int>`. `retryListening()` increments it when already in `CallPhase.Listening`, instead of re-setting the same phase value (which would not trigger `StateFlow` emission). `CallUIScreen` uses `LaunchedEffect(phase, listeningKey)` so STT restarts on every retry. STT blank results and errors call `retryListening()` directly — they never reach `handleVoiceCommand`.

### Caregiver profile calibration
`CaregiverSetupScreen` shows a horizontal scrollable list of fabric images at five fade levels (0%, 5%, 10%, 20%, 30%), rendered with `ColorFilter.colorMatrix` to simulate fading. The trusted person rates each as *Still fine / Borderline / Retire*. `computeOverallThreshold()` finds the first level rated Borderline or Retire and stores it as a single `Int` on `CaregiverProfile.fadeThreshold`. Passed into the Groq prompt so the AI calibrates language to that person's tolerance.

### Prompt rules (both prompts)
- Passive voice throughout
- Location-specific stain descriptions
- Soft comparative language for fading
- Never: "you should", "you must", "change your shirt"
- Caregiver referenced by name when provided
- Always ends with exactly: **Do you still want to wear it?**

Do not loosen these constraints — they exist to protect user autonomy and dignity.

### Audio routing
`AudioRouter` is created inside `CallViewModel.factory()` using `APPLICATION_KEY` app context. `transitionToSpeaking()` calls `routeToEarpiece()` (Out mode → `MODE_IN_CALL`) or `routeToSpeaker()` (Home mode → `MODE_NORMAL` + speakerphone). `onCleared()` calls `resetRouting()`. The `CallUIScreen` `DisposableEffect` also resets audio on dispose — the two resets are idempotent.

`AudioRouter` also contains a self-contained TTS engine (`initTts`, `speak`, `stop`, `shutdown`) that is **not currently used** by `CallViewModel`. TTS in the call flow is handled by a local `TextToSpeech` instance created directly inside `CallUIScreen`'s `DisposableEffect`.

### Telecom self-managed call
`TelecomHelper.startIncomingCall()` calls `TelecomManager.addNewIncomingCall()`. Creates a real system call visible on the lock screen. If the device blocks self-managed calls, the flow degrades silently to in-app audio — never crash.

### `GeminiApiService` naming
The class is named `GeminiApiService` and the file kept as `GeminiApiService.kt` for import compatibility. The implementation calls Groq, not Gemini. `ClaudeApiService.kt` is a deprecated `typealias` pointing here — do not add logic to it.

## Groq request format

**Endpoint:** `https://api.groq.com/openai/v1/chat/completions`  
**Model:** `meta-llama/llama-4-scout-17b-16e-instruct`  
**Auth:** `Authorization: Bearer <gsk_...>`

```json
{
  "model": "meta-llama/llama-4-scout-17b-16e-instruct",
  "messages": [
    { "role": "system", "content": "<SYSTEM_PROMPT or STAIN_ONLY_PROMPT>" },
    {
      "role": "user",
      "content": [
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<baseline>" } },
        { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<current>" } },
        { "type": "text",      "text": "<user prompt with caregiver name + threshold>" }
      ]
    }
  ],
  "max_tokens": 1024
}
```

Single-image (stain-only, always active) omits the first `image_url` part.  
Multi-turn (`requestMoreDetail`) appends an `assistant` message then a new `user` text message.  
Response parsed at: `choices[0].message.content`

## Build

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Requires a **Groq API key** (`gsk_...`) from console.groq.com. Entered at first launch, stored in SharedPreferences (not exported, not in source). Clear app data to re-enter: `adb shell pm clear com.clothcall`.

## Permissions required

| Permission | Used for |
|---|---|
| `CAMERA` | Photo capture in QuickScan + Wardrobe |
| `RECORD_AUDIO` | STT in CallUI and WardrobeScreen name input |
| `INTERNET` | Groq REST API calls |
| `MODIFY_AUDIO_SETTINGS` | Speakerphone / earpiece routing |
| `VIBRATE` | Ringing vibration pattern in CallUI |
| `MANAGE_OWN_CALLS` | Telecom self-managed PhoneAccount registration |

## What does not exist yet

- No tests (unit or instrumented).
- **No garment selector UI** — `selectedGarmentId` is always -1. The fading comparison path (two-image Groq request with `SYSTEM_PROMPT`) is coded but never triggered. To enable it, a garment picker needs to be added somewhere in the scan flow and wired to `prefs.selectedGarmentId`.
- `ScanResultHolder.conversationHistory` is populated but never read — multi-turn context beyond the first follow-up is not implemented.
- `CallViewModel.caregiverName` is hardcoded to `"ClothCall"` and does not show the active caregiver's name on the call screen.
- `AudioRouter`'s own TTS methods (`initTts`, `speak`, `shutdown`) are never called — the call flow uses a local `TextToSpeech` instance inside `CallUIScreen` instead.
- Wardrobe baseline comparison quality depends on lighting/angle consistency between the saved photo and the scan photo — no guidance is given to the user about this.
- `SpeechRecognizer.isRecognitionAvailable()` is checked in `CallUIScreen` before creating the recognizer but not in `WardrobeScreen`'s `NameGarmentScreen`, which could crash on devices without STT support.
