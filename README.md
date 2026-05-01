# Live Transcriber

A native Android app for offline-preferred live transcription.

The app targets Android API 35 so modern Android devices do not treat the APK as an old-target app.

## Features

- Start/stop microphone transcription from one screen.
- Transcript text fills most of the display and persists locally.
- Stop pauses transcription; Start continues appending to the current transcript.
- Copy is enabled only while recording is stopped.
- New is available only while the microphone is stopped. It clears the text area, creates a new saved transcript slot, and leaves the microphone off.
- Stores up to 10 transcripts in a circular local `SharedPreferences` buffer.
- Uses a microphone foreground service so active transcription continues while the screen is locked or another app is foregrounded.
- Stops recording after 1 hour outside the foreground.

## Offline Recognition

The implementation uses Android's on-device `SpeechRecognizer` on Android 12+ when available, and falls back to the platform recognizer with `RecognizerIntent.EXTRA_PREFER_OFFLINE` on older or unsupported devices. For fully offline behavior, the device needs an installed on-device/offline speech recognition engine for the active language.

### Verify Offline Speech Recognition

The most reliable test is to force the device offline:

1. Install and open the app once while online.
2. Grant microphone permission.
3. Turn on Airplane mode and turn Wi-Fi off.
4. Open the app, tap Start, and speak a short sentence.
5. If text appears, offline speech recognition is available for the active device language.
6. If no text appears, install or update the offline language pack, then repeat the test.

You can also inspect the configured recognizer with ADB:

```sh
adb shell settings get secure voice_recognition_service
```

This should print a recognizer component, commonly from Google or the device manufacturer. A configured recognizer does not guarantee that the needed offline language pack is installed, so still run the Airplane mode test.

### Install Offline Language Packs

Menu names vary by Android version and manufacturer. Try these paths in order:

1. Android Settings:
   `Settings > System > Languages & input > Speech > Offline speech recognition`

2. Google settings:
   `Settings > Google > Settings for Google apps > Search, Assistant & Voice > Voice > Offline speech recognition`

3. Google voice typing:
   `Settings > System > Languages & input > On-screen keyboard > Google voice typing > Offline speech recognition`

4. Live Transcribe, on Pixel and supported Android devices:
   `Live Transcribe > Settings > More settings > Primary language` or `Secondary language`

In the language list, download the active language used by the phone/app. If there is an Auto-update tab, enable automatic updates for installed languages.

If none of these menus exist, install or update these apps from Google Play, then check again:

- Speech Services by Google
- Google
- Live Transcribe & Sound Notifications

Some non-Google Android builds do not expose an offline recognizer for third-party apps. In that case, the app can still request offline recognition, but the final behavior depends on the recognizer installed on the device.

## Build

Install Android Studio or a compatible Android SDK and JDK, then run:

```sh
./gradlew assembleDebug
```

If macOS says it cannot locate a Java runtime, install JDK 17 and make it available to the shell. With Homebrew:

```sh
brew install openjdk@17
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Add those `export` lines to your shell profile if you want them to apply to new terminal sessions.

## Test On A Device

1. Enable Developer options on the Android device.
2. Enable USB debugging.
3. Connect the device over USB and approve the debugging prompt on the device.
4. Make sure `adb` is available. If `adb` is already on your `PATH`, this should print its location:

```sh
which adb
```

If that prints nothing, use the Android SDK copy directly:

```sh
~/Library/Android/sdk/platform-tools/adb version
```

To make `adb` available in future terminal sessions, add platform-tools to your shell profile:

```sh
echo 'export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"' >> ~/.bash_profile
source ~/.bash_profile
```

5. Confirm the device is visible:

```sh
adb devices
```

If `adb` is still not on your `PATH`, use:

```sh
~/Library/Android/sdk/platform-tools/adb devices
```

6. Build and install the debug APK:

```sh
./gradlew installDebug
```

If Android previously warned that the app was built for an old or less secure Android version, reinstall the current debug build. This project now targets API 35:

```sh
./gradlew installDebug
```

7. Launch the app from the device launcher, or run:

```sh
adb shell am start -n com.example.realtimetranscriber/.MainActivity
```

8. Grant microphone permission when prompted. On Android 13+, also allow notifications so the foreground microphone service can show its required notification.

## Manual Test Checklist

- Tap Start and speak. Text should appear in the transcript area.
- Tap Stop. Transcription should pause, Copy should become enabled, and New should become enabled.
- Tap Start again. New speech should append to the existing transcript.
- Tap Copy while stopped, then paste into another app to confirm clipboard contents.
- Tap New while stopped. The transcript area should clear and the mic should remain off.
- Start recording, lock the phone or switch to another app, then return. Transcription should continue while the foreground service notification is active.
- Leave recording in the background. It should stop automatically after 1 hour outside the foreground.

## Logs

Use `adb logcat` while testing on a device:

```sh
adb logcat | grep realtimetranscriber
```

If the app does not transcribe offline, follow the verification and language-pack steps in the Offline Recognition section above.
