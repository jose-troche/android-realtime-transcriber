# Live Transcriber

Native Android live transcription with online-first speech recognition, offline fallback, and local transcript history.

The app targets Android API 35.

## Features

- Start/Stop controls microphone transcription.
- Stop pauses recording; Start resumes and appends to the current transcript.
- Copy, Share, Export, New, Delete, and Select are available only while the microphone is stopped.
- Share sends the current transcript as a `.txt` attachment to another app through Android's share sheet.
- Export saves the current transcript as a local `.txt` file.
- New creates an empty transcript slot and leaves the microphone off.
- Select loads one of the saved non-empty transcriptions.
- Delete removes the active transcription and selects the next saved one, or clears the screen if none remain.
- Up to 10 transcriptions are stored locally in a circular `SharedPreferences` buffer.
- Active recording runs in a foreground microphone service while the screen is locked or another app is open.
- In-progress words are preserved more reliably when Android interrupts and restarts recognition during app switches or lock/screen-saver transitions.
- Background recording continues until the user taps Stop.

## Speech Recognition

Recording starts with Android's regular `SpeechRecognizer`, which may use an online recognition service. If that recognizer reports a network or server failure, the service switches to offline mode and restarts recognition with `RecognizerIntent.EXTRA_PREFER_OFFLINE`.

On Android 12+, offline fallback uses the on-device speech recognizer when available. Fully offline transcription still depends on the device having an offline recognizer and language pack installed for the active language.

## Build

Requirements:

- Android Studio or Android SDK command-line tools
- Android SDK platform 35
- JDK 17

Build the debug APK:

```sh
./gradlew assembleDebug
```

Install on a connected device:

```sh
./gradlew installDebug
```

If macOS cannot locate Java, install and expose JDK 17:

```sh
brew install openjdk@17
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

## Device Setup

1. Enable Developer options on the device.
2. Enable USB debugging.
3. Connect the device over USB and approve the debugging prompt.
4. Confirm ADB can see the device:

```sh
adb devices
```

If `adb` is not found, add Android platform-tools to your shell:

```sh
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.bash_profile
source ~/.bash_profile
```

Launch the installed app:

```sh
adb shell am start -n com.example.realtimetranscriber/.MainActivity
```

Grant microphone permission. On Android 13+, also allow notifications so the foreground microphone service can show its required notification.

When you use Export:

- On Android 10 and newer, files are saved to `Downloads/Transcriber`.
- On older Android versions, files are saved to the app-specific downloads directory under `Transcriber`.

## Offline Setup

To verify offline recognition:

1. Open the app once while online and grant permissions.
2. Turn on Airplane mode and turn Wi-Fi off.
3. Tap Start and speak a short sentence.
4. If text appears, offline recognition works for the active language.

If it does not work, install or update the offline language pack. Menu names vary, but common paths are:

- `Settings > System > Languages & input > Speech > Offline speech recognition`
- `Settings > Google > Settings for Google apps > Search, Assistant & Voice > Voice > Offline speech recognition`
- `Settings > System > Languages & input > On-screen keyboard > Google voice typing > Offline speech recognition`
- `Live Transcribe > Settings > More settings > Primary language`

If those menus are missing, update these apps from Google Play:

- Speech Services by Google
- Google
- Live Transcribe & Sound Notifications

Some Android builds do not expose offline recognition to third-party apps, even when the app requests it.

## Manual Test Checklist

- Start recording and speak; text appears in the transcript area.
- Stop recording; Copy, Share, Export, New, Delete, and Select become available as appropriate.
- Start again; new speech appends to the selected transcript.
- Copy while stopped, then paste into another app.
- Share while stopped, then confirm another app receives a `.txt` attachment.
- Export while stopped, then confirm a `.txt` file is saved locally.
- New while stopped clears the text and keeps the mic off.
- Select while stopped loads a saved transcript and keeps the mic off.
- Delete while stopped removes the active transcript.
- Lock the phone or switch apps while recording; transcription continues with the foreground service notification active.
- Speak during an app switch or lock/unlock transition and confirm words are not dropped when recognition restarts.
- Leave recording in the background for an extended period and confirm it continues until you tap Stop.

## Logs

```sh
adb logcat | grep realtimetranscriber
```
