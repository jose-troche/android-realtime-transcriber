package com.example.realtimetranscriber;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 42;

    private TextView transcriptView;
    private TextView statusView;
    private Button micButton;
    private Button copyButton;
    private Button newButton;
    private Button deleteButton;
    private Button selectButton;
    private TranscriptionStore localStore;

    private TranscriptionService service;
    private boolean bound;
    private boolean recording;
    private String latestTranscript = "";
    private PendingAction pendingAction = PendingAction.NONE;

    private enum PendingAction {
        NONE,
        START
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            TranscriptionService.LocalBinder localBinder = (TranscriptionService.LocalBinder) binder;
            service = localBinder.getService();
            bound = true;
            service.setListener(serviceListener);
            service.setAppInForeground(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    private final TranscriptionService.ServiceListener serviceListener =
            new TranscriptionService.ServiceListener() {
                @Override
                public void onStateChanged(String transcript, boolean isRecording, String status) {
                    latestTranscript = transcript == null ? "" : transcript;
                    recording = isRecording;
                    transcriptView.setText(latestTranscript);
                    statusView.setText(status);
                    updateButtons();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, TranscriptionService.class);
        bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (bound && service != null) {
            service.setAppInForeground(false);
            service.setListener(null);
        }
        if (bound) {
            unbindService(connection);
            bound = false;
            service = null;
        }
        super.onStop();
    }

    private void buildUi() {
        int padding = dp(4);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // Slightly reduce top inset so status is visible without wasting space; keep generous bottom
        root.setPadding(padding, dp(44), padding, dp(44));
        root.setBackgroundColor(Color.rgb(0, 0, 0));

        // local store for accessing saved transcriptions when UI is visible
        localStore = new TranscriptionStore(this);

        statusView = new TextView(this);
        statusView.setText("Ready");
        statusView.setTextColor(Color.rgb(231, 234, 241));
        statusView.setTextSize(15);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView transcriptContainer = new ScrollView(this);
        transcriptContainer.setFillViewport(true);
        transcriptContainer.setBackgroundColor(Color.DKGRAY);
        transcriptContainer.setPadding(dp(8), dp(8), dp(8), dp(8));

        transcriptView = new TextView(this);
        transcriptView.setTextSize(18);
        transcriptView.setTextColor(Color.WHITE);
        transcriptView.setGravity(Gravity.START | Gravity.TOP);
        transcriptView.setMinLines(12);
        transcriptView.setMovementMethod(new ScrollingMovementMethod());
        transcriptContainer.addView(transcriptView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams transcriptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        transcriptParams.setMargins(0, 0, 0, 0);
        root.addView(transcriptContainer, transcriptParams);

        // Controls: single row, with extra bottom padding so buttons aren't flush to edge
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        // keep small inner padding, but add larger bottom padding so buttons are comfortably above system bars
        controls.setPadding(dp(2), dp(2), dp(2), dp(2));

        micButton = new Button(this);
        micButton.setText("Start");
        micButton.setAllCaps(false);
        micButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        micButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (recording) {
                    sendServiceAction(TranscriptionService.ACTION_STOP_RECORDING);
                } else {
                    startOrRequestPermission(PendingAction.START);
                }
            }
        });
        controls.addView(micButton, buttonParams());

        copyButton = new Button(this);
        copyButton.setText("Copy");
        copyButton.setAllCaps(false);
        copyButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTranscript();
            }
        });
        controls.addView(copyButton, buttonParams());

        newButton = new Button(this);
        newButton.setText("New");
        newButton.setAllCaps(false);
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendServiceAction(TranscriptionService.ACTION_START_NEW);
            }
        });
        controls.addView(newButton, buttonParams());

        // Delete button: removes the current active slot and advances to the next saved slot (or clears)
        deleteButton = new Button(this);
        deleteButton.setText("Delete");
        deleteButton.setAllCaps(false);
        deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete transcription")
                        .setMessage("Delete the current transcription?")
                        .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                localStore.deleteActiveSlot();
                                latestTranscript = localStore.getActiveText();
                                transcriptView.setText(latestTranscript);
                                Toast.makeText(MainActivity.this, "Deleted", Toast.LENGTH_SHORT).show();
                                updateButtons();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        controls.addView(deleteButton, buttonParams());

        // Select button: when not recording, allows picking any saved transcription
        selectButton = new Button(this);
        selectButton.setText("Select");
        selectButton.setAllCaps(false);
        selectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Retrieve non-empty slot indices and show previews. Selecting a slot makes it the active slot
                final java.util.List<Integer> slots = localStore.getNonEmptySlots();
                if (slots == null || slots.isEmpty()) {
                    Toast.makeText(MainActivity.this, "No saved transcriptions", Toast.LENGTH_SHORT).show();
                    return;
                }
                String[] arr = new String[slots.size()];
                for (int i = 0; i < slots.size(); i++) {
                    String full = localStore.getTextForSlot(slots.get(i));
                    arr[i] = previewFor(full);
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Select transcription")
                        .setItems(arr, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                int slot = slots.get(which);
                                // Make the chosen slot active, and load its full text
                                localStore.setActiveSlot(slot);
                                String chosen = localStore.getTextForSlot(slot);
                                latestTranscript = chosen == null ? "" : chosen;
                                transcriptView.setText(latestTranscript);
                                updateButtons();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
        controls.addView(selectButton, buttonParams());

        root.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        updateButtons();
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private void startOrRequestPermission(PendingAction action) {
        if (hasAudioPermission()) {
            runPendingAction(action);
            return;
        }
        pendingAction = action;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }
        if (hasAudioPermission()) {
            PendingAction action = pendingAction;
            pendingAction = PendingAction.NONE;
            runPendingAction(action);
        } else {
            pendingAction = PendingAction.NONE;
            Toast.makeText(this, "Microphone permission is needed for transcription.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private String[] requiredPermissions() {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return permissions.toArray(new String[0]);
    }

    private void runPendingAction(PendingAction action) {
        if (action == PendingAction.START) {
            sendServiceAction(TranscriptionService.ACTION_START_RECORDING);
        }
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, TranscriptionService.class);
        intent.setAction(action);
        boolean startsRecording = TranscriptionService.ACTION_START_RECORDING.equals(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && startsRecording) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void copyTranscript() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", latestTranscript));
        Toast.makeText(this, "Transcription copied", Toast.LENGTH_SHORT).show();
    }

    private void updateButtons() {
        micButton.setText(recording ? "Stop" : "Start");
        copyButton.setEnabled(!recording && !latestTranscript.trim().isEmpty());
        newButton.setEnabled(!recording);
        if (selectButton != null && localStore != null) {
            selectButton.setEnabled(!recording && !localStore.getNonEmptySlots().isEmpty());
        }
        if (deleteButton != null && localStore != null) {
            deleteButton.setEnabled(!recording && !localStore.getActiveText().trim().isEmpty());
        }
    }

    private String previewFor(String text) {
        if (text == null) return "";
        // collapse whitespace and newlines into single spaces
        String oneLine = text.replaceAll("\\s+", " ").trim();
        // limit to approx 34 characters to fit a single dialog line on most devices
        int max = 34;
        if (oneLine.length() <= max) return oneLine;
        return oneLine.substring(0, Math.min(oneLine.length(), max)).trim() + "…";
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
