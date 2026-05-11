package com.example.realtimetranscriber;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

public class TranscriptionService extends Service {
    static final String ACTION_START_RECORDING = "com.example.realtimetranscriber.START_RECORDING";
    static final String ACTION_STOP_RECORDING = "com.example.realtimetranscriber.STOP_RECORDING";
    static final String ACTION_START_NEW = "com.example.realtimetranscriber.START_NEW";

    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "live_transcription";
    private static final long RESTART_DELAY_MS = 350L;
    private static final long MAX_RESTART_DELAY_MS = 5000L;
    private static final long STOP_FLUSH_TIMEOUT_MS = 1500L;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder committedText = new StringBuilder();

    private TranscriptionStore store;
    private SpeechRecognizer recognizer;
    private ServiceListener listener;
    private boolean recording;
    private boolean appInForeground;
    private boolean manuallyStopping;
    private boolean finalizingStop;
    private boolean offlineMode;
    private String partialText = "";
    private String status = "Ready";
    private long recognizerRestartDelayMs = RESTART_DELAY_MS;

    public final class LocalBinder extends Binder {
        TranscriptionService getService() {
            return TranscriptionService.this;
        }
    }

    interface ServiceListener {
        void onStateChanged(String transcript, boolean isRecording, String status);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new TranscriptionStore(this);
        committedText.setLength(0);
        committedText.append(store.getActiveText());
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START_NEW.equals(action)) {
            startNewTranscription();
        } else if (ACTION_START_RECORDING.equals(action)) {
            startRecording();
        } else if (ACTION_STOP_RECORDING.equals(action)) {
            stopRecording();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyRecognizerNow();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    void setListener(ServiceListener listener) {
        this.listener = listener;
        notifyStateChanged();
    }

    void setAppInForeground(boolean inForeground) {
        appInForeground = inForeground;
    }

    String getTranscript() {
        return getDisplayedTranscript();
    }

    boolean isRecording() {
        return recording || finalizingStop;
    }

    void startNewTranscription() {
        if (recording) {
            recording = false;
            manuallyStopping = true;
            stopRecognizer();
            stopForegroundCompat();
        }
        store.startNewTranscription();
        committedText.setLength(0);
        partialText = "";
        status = "New transcription";
        notifyStateChanged();
        stopSelfIfIdle();
    }

    void startRecording() {
        if (recording || finalizingStop) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is required";
            notifyStateChanged();
            stopSelfIfIdle();
            return;
        }

        recording = true;
        manuallyStopping = false;
        finalizingStop = false;
        offlineMode = false;
        recognizerRestartDelayMs = RESTART_DELAY_MS;
        status = "Listening online";
        startForeground(NOTIFICATION_ID, buildNotification("Listening online"));
        startRecognizer();
        notifyStateChanged();
    }

    void stopRecording() {
        if (!recording) {
            return;
        }
        recording = false;
        manuallyStopping = true;
        finalizingStop = true;
        status = "Finishing transcription";
        notifyStateChanged();
        flushRecognizerBeforeStop();
    }

    void selectTranscription(int slot) {
        if (recording) {
            return;
        }
        store.setActiveSlot(slot);
        committedText.setLength(0);
        committedText.append(store.getActiveText());
        partialText = "";
        status = "Selected transcription";
        notifyStateChanged();
    }

    void deleteActiveTranscription() {
        if (recording) {
            return;
        }
        store.deleteActiveSlot();
        committedText.setLength(0);
        committedText.append(store.getActiveText());
        partialText = "";
        status = committedText.length() == 0 ? "No saved transcription selected" : "Selected next transcription";
        notifyStateChanged();
    }

    private void startRecognizer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!recording) {
                    return;
                }
                try {
                    if (recognizer == null) {
                        recognizer = createSpeechRecognizer();
                        recognizer.setRecognitionListener(recognitionListener);
                    }
                    recognizer.startListening(buildRecognizerIntent());
                } catch (RuntimeException exception) {
                    destroyRecognizerNow();
                    if (!offlineMode) {
                        offlineMode = true;
                        status = "Online unavailable; switching offline";
                    } else {
                        status = "Speech recognizer could not start";
                    }
                    notifyStateChanged();
                    scheduleRecognizerRestart();
                }
            }
        });
    }

    private SpeechRecognizer createSpeechRecognizer() {
        if (offlineMode
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        }
        if (!offlineMode && SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Using online recognizer";
            return SpeechRecognizer.createSpeechRecognizer(this);
        }
        status = offlineMode ? "Using offline recognizer" : "Using available recognizer";
        return SpeechRecognizer.createSpeechRecognizer(this);
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offlineMode);
        return intent;
    }

    private void stopRecognizer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                mainHandler.removeCallbacks(recognizerRestartRunnable);
                destroyRecognizerNow();
            }
        });
    }

    private void flushRecognizerBeforeStop() {
        mainHandler.removeCallbacks(finalizeStopRunnable);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recognizer == null) {
                    finishStoppedRecording();
                    return;
                }
                try {
                    recognizer.stopListening();
                    mainHandler.postDelayed(finalizeStopRunnable, STOP_FLUSH_TIMEOUT_MS);
                } catch (RuntimeException ignored) {
                    finishStoppedRecording();
                }
            }
        });
    }

    private void destroyRecognizerNow() {
        if (recognizer == null) {
            return;
        }
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (RuntimeException ignored) {
            // The framework recognizer can throw while shutting down across binder death.
        }
        recognizer = null;
    }

    private void scheduleRecognizerRestart() {
        mainHandler.removeCallbacks(recognizerRestartRunnable);
        long delayMs = recognizerRestartDelayMs;
        recognizerRestartDelayMs = Math.min(recognizerRestartDelayMs * 2, MAX_RESTART_DELAY_MS);
        mainHandler.postDelayed(recognizerRestartRunnable, delayMs);
    }

    private final Runnable recognizerRestartRunnable = new Runnable() {
        @Override
        public void run() {
            if (recording && !manuallyStopping) {
                startRecognizer();
            }
        }
    };

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            recognizerRestartDelayMs = RESTART_DELAY_MS;
            status = offlineMode ? "Listening offline" : "Listening online";
            notifyStateChanged();
        }

        @Override
        public void onBeginningOfSpeech() {
            status = "Transcribing";
            notifyStateChanged();
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
            status = "Processing";
            notifyStateChanged();
        }

        @Override
        public void onError(int error) {
            if (finalizingStop) {
                finishStoppedRecording();
                return;
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                partialText = "";
                stopRecording();
                status = "Microphone permission is required";
                notifyStateChanged();
                return;
            }
            if (recording && !manuallyStopping) {
                boolean shouldCommitPartial = error != SpeechRecognizer.ERROR_NO_MATCH;
                if (shouldCommitPartial) {
                    commitPartialTextForRestart();
                }
                if (!offlineMode && shouldFallbackOffline(error)) {
                    offlineMode = true;
                    destroyRecognizerNow();
                    status = "Online unavailable; switching offline";
                } else {
                    destroyRecognizerNow();
                    status = error == SpeechRecognizer.ERROR_NO_MATCH
                            ? (offlineMode ? "Listening offline" : "Listening online")
                            : "Restarting recognizer";
                }
                notifyStateChanged();
                scheduleRecognizerRestart();
            }
        }

        @Override
        public void onResults(Bundle results) {
            appendBestResult(results);
            partialText = "";
            persistCommittedTranscript();
            if (finalizingStop) {
                finishStoppedRecording();
                return;
            }
            notifyStateChanged();
            if (recording && !manuallyStopping) {
                scheduleRecognizerRestart();
            }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (matches != null && !matches.isEmpty()) {
                partialText = matches.get(0);
                notifyStateChanged();
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    private void appendBestResult(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null || matches.isEmpty()) {
            return;
        }
        appendPhrase(matches.get(0));
    }

    private void appendPhrase(String phrase) {
        if (phrase == null) {
            return;
        }
        String cleanPhrase = phrase.trim();
        if (cleanPhrase.isEmpty()) {
            return;
        }
        String textToAppend = trimRepeatedPrefix(cleanPhrase);
        if (textToAppend.isEmpty()) {
            return;
        }
        if (committedText.length() > 0 && !Character.isWhitespace(committedText.charAt(committedText.length() - 1))) {
            committedText.append(' ');
        }
        committedText.append(textToAppend);
    }

    private String getDisplayedTranscript() {
        String partial = partialText == null ? "" : partialText.trim();
        if (partial.isEmpty()) {
            return committedText.toString();
        }
        String committed = committedText.toString();
        if (committed.trim().isEmpty()) {
            return partial;
        }
        return committed + " " + partial;
    }

    private void commitPartialText() {
        appendPhrase(partialText);
        partialText = "";
    }

    private void commitPartialTextForRestart() {
        String partial = partialText == null ? "" : partialText.trim();
        if (partial.isEmpty()) {
            return;
        }
        appendPhrase(partial);
        partialText = "";
        persistCommittedTranscript();
    }

    private String trimRepeatedPrefix(String phrase) {
        if (committedText.length() == 0) {
            return phrase;
        }
        String committed = committedText.toString().trim();
        if (committed.isEmpty()) {
            return phrase;
        }

        String[] committedWords = committed.split("\\s+");
        String[] phraseWords = phrase.split("\\s+");
        int maxOverlap = Math.min(committedWords.length, phraseWords.length);
        int overlapWords = 0;
        for (int candidate = maxOverlap; candidate > 0; candidate--) {
            if (matchesWordOverlap(committedWords, phraseWords, candidate)) {
                overlapWords = candidate;
                break;
            }
        }
        if (overlapWords == 0) {
            return phrase;
        }
        if (overlapWords == phraseWords.length) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = overlapWords; index < phraseWords.length; index++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(phraseWords[index]);
        }
        return builder.toString();
    }

    private boolean matchesWordOverlap(String[] committedWords, String[] phraseWords, int overlapWords) {
        int committedStart = committedWords.length - overlapWords;
        for (int index = 0; index < overlapWords; index++) {
            if (!committedWords[committedStart + index].equalsIgnoreCase(phraseWords[index])) {
                return false;
            }
        }
        return true;
    }

    private void persistCommittedTranscript() {
        store.updateActiveText(committedText.toString());
    }

    private void finishStoppedRecording() {
        mainHandler.removeCallbacks(finalizeStopRunnable);
        mainHandler.removeCallbacks(recognizerRestartRunnable);
        commitPartialText();
        persistCommittedTranscript();
        destroyRecognizerNow();
        status = "Paused";
        finalizingStop = false;
        stopForegroundCompat();
        notifyStateChanged();
        stopSelfIfIdle();
    }

    private final Runnable finalizeStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (finalizingStop) {
                finishStoppedRecording();
            }
        }
    };

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged(getDisplayedTranscript(), recording || finalizingStop, status);
        }
    }

    private boolean shouldFallbackOffline(int error) {
        return error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || error == SpeechRecognizer.ERROR_SERVER
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED);
    }

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return newNotificationBuilder()
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Live transcription active")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder newNotificationBuilder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID);
        }
        return new Notification.Builder(this);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Live transcription",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows when offline live transcription is using the microphone.");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void stopSelfIfIdle() {
        if (!recording && listener == null) {
            stopSelf();
        }
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
