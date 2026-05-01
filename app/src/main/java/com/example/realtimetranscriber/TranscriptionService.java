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
import android.os.SystemClock;
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
    private static final long MAX_BACKGROUND_RECORDING_MS = 60L * 60L * 1000L;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder committedText = new StringBuilder();

    private TranscriptionStore store;
    private SpeechRecognizer recognizer;
    private ServiceListener listener;
    private boolean recording;
    private boolean appInForeground;
    private boolean manuallyStopping;
    private String partialText = "";
    private String status = "Ready";
    private long backgroundStartedAt = 0L;

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
            stopRecording(false);
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
        if (inForeground) {
            backgroundStartedAt = 0L;
            mainHandler.removeCallbacks(backgroundStopRunnable);
            return;
        }
        backgroundStartedAt = SystemClock.elapsedRealtime();
        scheduleBackgroundStop();
    }

    String getTranscript() {
        return getVisibleTranscript();
    }

    boolean isRecording() {
        return recording;
    }

    void startNewTranscription() {
        if (recording) {
            recording = false;
            manuallyStopping = true;
            stopRecognizer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            mainHandler.removeCallbacks(backgroundStopRunnable);
        }
        store.startNewTranscription();
        committedText.setLength(0);
        partialText = "";
        status = "New transcription";
        notifyStateChanged();
        stopSelfIfIdle();
    }

    void startRecording() {
        if (recording) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "Microphone permission is required";
            notifyStateChanged();
            stopSelfIfIdle();
            return;
        }

        recording = true;
        manuallyStopping = false;
        status = "Listening offline";
        startForeground(NOTIFICATION_ID, buildNotification("Listening"));
        startRecognizer();
        if (!appInForeground) {
            if (backgroundStartedAt == 0L) {
                backgroundStartedAt = SystemClock.elapsedRealtime();
            }
            scheduleBackgroundStop();
        }
        notifyStateChanged();
    }

    void stopRecording(boolean timedOut) {
        if (!recording && !timedOut) {
            return;
        }
        recording = false;
        manuallyStopping = true;
        stopRecognizer();
        status = timedOut ? "Stopped after 1 hour in background" : "Paused";
        persistVisibleTranscript();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        mainHandler.removeCallbacks(backgroundStopRunnable);
        notifyStateChanged();
        stopSelfIfIdle();
    }

    private void startRecognizer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!recording) {
                    return;
                }
                if (recognizer == null) {
                    recognizer = createSpeechRecognizer();
                    recognizer.setRecognitionListener(recognitionListener);
                }
                try {
                    recognizer.startListening(buildRecognizerIntent());
                } catch (RuntimeException exception) {
                    status = "Speech recognizer could not start";
                    notifyStateChanged();
                    scheduleRecognizerRestart();
                }
            }
        });
    }

    private SpeechRecognizer createSpeechRecognizer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
        }
        status = "Using offline-preferred recognizer";
        return SpeechRecognizer.createSpeechRecognizer(this);
    }

    private Intent buildRecognizerIntent() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return intent;
    }

    private void stopRecognizer() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                destroyRecognizerNow();
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (recording && !manuallyStopping) {
                    startRecognizer();
                }
            }
        }, RESTART_DELAY_MS);
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            status = "Listening offline";
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
            partialText = "";
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                stopRecording(false);
                status = "Microphone permission is required";
                notifyStateChanged();
                return;
            }
            if (recording && !manuallyStopping) {
                status = error == SpeechRecognizer.ERROR_NO_MATCH
                        ? "Listening offline"
                        : "Restarting recognizer";
                notifyStateChanged();
                scheduleRecognizerRestart();
            }
        }

        @Override
        public void onResults(Bundle results) {
            appendBestResult(results);
            partialText = "";
            persistVisibleTranscript();
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
                persistVisibleTranscript();
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
        if (committedText.length() > 0 && !Character.isWhitespace(committedText.charAt(committedText.length() - 1))) {
            committedText.append(' ');
        }
        committedText.append(cleanPhrase);
    }

    private String getVisibleTranscript() {
        if (partialText == null || partialText.trim().isEmpty()) {
            return committedText.toString();
        }
        String committed = committedText.toString();
        if (committed.trim().isEmpty()) {
            return partialText.trim();
        }
        return committed + " " + partialText.trim();
    }

    private void persistVisibleTranscript() {
        store.updateActiveText(getVisibleTranscript());
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged(getVisibleTranscript(), recording, status);
        }
    }

    private void scheduleBackgroundStop() {
        mainHandler.removeCallbacks(backgroundStopRunnable);
        if (recording && !appInForeground) {
            mainHandler.postDelayed(backgroundStopRunnable, MAX_BACKGROUND_RECORDING_MS);
        }
    }

    private final Runnable backgroundStopRunnable = new Runnable() {
        @Override
        public void run() {
            long elapsed = backgroundStartedAt == 0L ? 0L : SystemClock.elapsedRealtime() - backgroundStartedAt;
            if (recording && !appInForeground && elapsed >= MAX_BACKGROUND_RECORDING_MS) {
                stopRecording(true);
            }
        }
    };

    private Notification buildNotification(String text) {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_IMMUTABLE
                        : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Live transcription active")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
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
}
