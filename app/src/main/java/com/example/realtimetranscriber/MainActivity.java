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
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 42;
    private static final int CONTROL_BUTTON_TEXT_SP = 13;

    private TextView transcriptView;
    private ScrollView transcriptContainer;
    private TextView statusView;
    private Button micButton;
    private Button copyButton;
    private Button newButton;
    private Button deleteButton;
    private Button selectButton;
    private View toastOverlay;
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
                    scrollTranscriptToBottom();
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
        statusView.setText(R.string.status_ready);
        statusView.setTextColor(Color.rgb(231, 234, 241));
        statusView.setTextSize(15);
        root.addView(statusView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        transcriptContainer = new ScrollView(this);
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
        micButton.setText(R.string.button_start);
        micButton.setAllCaps(false);
        micButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, CONTROL_BUTTON_TEXT_SP);
        setButtonIcon(micButton, android.R.drawable.ic_btn_speak_now);
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
        copyButton.setText(R.string.button_copy);
        copyButton.setAllCaps(false);
        copyButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, CONTROL_BUTTON_TEXT_SP);
        setButtonIcon(copyButton, android.R.drawable.ic_menu_upload);
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyTranscript();
            }
        });
        controls.addView(copyButton, buttonParams());

        newButton = new Button(this);
        newButton.setText(R.string.button_new);
        newButton.setAllCaps(false);
        newButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, CONTROL_BUTTON_TEXT_SP);
        setButtonIcon(newButton, android.R.drawable.ic_menu_add);
        newButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendServiceAction(TranscriptionService.ACTION_START_NEW);
            }
        });
        controls.addView(newButton, buttonParams());

        // Delete button: removes the current active slot and advances to the next saved slot (or clears)
        deleteButton = new Button(this);
        deleteButton.setText(R.string.button_delete);
        deleteButton.setAllCaps(false);
        deleteButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, CONTROL_BUTTON_TEXT_SP);
        setButtonIcon(deleteButton, android.R.drawable.ic_menu_delete);
        deleteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Delete transcription")
                        .setMessage("Delete the current transcription?")
                        .setPositiveButton("Delete", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                if (bound && service != null) {
                                    service.deleteActiveTranscription();
                                } else {
                                    localStore.deleteActiveSlot();
                                    latestTranscript = localStore.getActiveText();
                                    transcriptView.setText(latestTranscript);
                                    scrollTranscriptToBottom();
                                }
                                showToast("Deleted", Toast.LENGTH_SHORT);
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
        selectButton.setText(R.string.button_select);
        selectButton.setAllCaps(false);
        selectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, CONTROL_BUTTON_TEXT_SP);
        setButtonIcon(selectButton, android.R.drawable.ic_menu_sort_by_size);
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Retrieve non-empty slot indices and show previews. Selecting a slot makes it the active slot
                final java.util.List<Integer> slots = localStore.getNonEmptySlotsChronological();
                if (slots == null || slots.isEmpty()) {
                    showToast("No saved transcriptions", Toast.LENGTH_SHORT);
                    return;
                }
                final int activeSlot = localStore.getActiveSlot();
                final String[] arr = new String[slots.size()];
                for (int i = 0; i < slots.size(); i++) {
                    int slot = slots.get(i);
                    String full = localStore.getTextForSlot(slot);
                    arr[i] = selectLabelFor(slot, full);
                }
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                        MainActivity.this,
                        android.R.layout.simple_list_item_1,
                        arr) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View view = super.getView(position, convertView, parent);
                        TextView textView = (TextView) view.findViewById(android.R.id.text1);
                        boolean isActive = slots.get(position) == activeSlot;
                        textView.setSingleLine(false);
                        textView.setMaxLines(2);
                        textView.setEllipsize(TextUtils.TruncateAt.END);
                        textView.setTypeface(Typeface.DEFAULT, isActive ? Typeface.BOLD : Typeface.NORMAL);
                        textView.setTextColor(isActive ? Color.rgb(30, 64, 175) : Color.rgb(31, 41, 55));
                        textView.setPadding(
                                textView.getPaddingLeft(),
                                dp(10),
                                textView.getPaddingRight(),
                                dp(10));
                        view.setBackgroundColor(isActive ? Color.rgb(219, 234, 254) : Color.TRANSPARENT);
                        return view;
                    }
                };
                final ListView listView = new ListView(MainActivity.this);
                listView.setAdapter(adapter);
                listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                listView.setDividerHeight(1);
                listView.setVerticalScrollBarEnabled(true);
                int activePosition = slots.indexOf(activeSlot);
                if (activePosition >= 0) {
                    listView.setItemChecked(activePosition, true);
                    listView.setSelection(activePosition);
                }
                final int listHeight = Math.min(dp(560), dp(64) * Math.min(slots.size(), 8));
                final FrameLayout listContainer = new FrameLayout(MainActivity.this);
                listContainer.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        listHeight));
                listContainer.addView(listView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                final AlertDialog selectDialog = new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Select transcription")
                        .setView(listContainer)
                        .setNegativeButton("Cancel", null)
                        .show();
                ViewGroup.LayoutParams containerParams = listContainer.getLayoutParams();
                containerParams.height = listHeight;
                containerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                listContainer.setLayoutParams(containerParams);
                listView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(android.widget.AdapterView<?> parent, View view, int which, long id) {
                        int slot = slots.get(which);
                        if (bound && service != null) {
                            service.selectTranscription(slot);
                        } else {
                            localStore.setActiveSlot(slot);
                            String chosen = localStore.getTextForSlot(slot);
                            latestTranscript = chosen == null ? "" : chosen;
                            transcriptView.setText(latestTranscript);
                            scrollTranscriptToBottom();
                        }
                        updateButtons();
                        selectDialog.dismiss();
                    }
                });
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
        params.setMargins(dp(1), 0, dp(1), 0);
        return params;
    }

    private void setButtonIcon(Button button, int drawableResource) {
        button.setSingleLine(true);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setHorizontallyScrolling(true);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinEms(0);
        button.setPadding(dp(9), dp(8), dp(6), dp(8));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_enabled},
                        new int[]{-android.R.attr.state_enabled}
                },
                new int[]{
                        Color.rgb(31, 41, 55),
                        Color.rgb(112, 124, 144)
                }));

        Drawable icon = getDrawable(drawableResource);
        if (icon == null) {
            return;
        }
        int iconSize = dp(16);
        icon.setBounds(0, 0, iconSize, iconSize);
        icon.setTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_enabled},
                        new int[]{-android.R.attr.state_enabled}
                },
                new int[]{
                        Color.rgb(55, 65, 81),
                        Color.rgb(112, 124, 144)
                }));
        button.setCompoundDrawablePadding(dp(1));
        button.setCompoundDrawables(icon, null, null, null);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setIncludeFontPadding(false);
    }

    private void startOrRequestPermission(PendingAction action) {
        if (hasAudioPermission()) {
            runPendingAction(action);
            return;
        }
        pendingAction = action;
        requestPermissions(requiredPermissions(), PERMISSION_REQUEST_CODE);
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
            showToast("Microphone permission is needed for transcription.", Toast.LENGTH_LONG);
        }
    }

    private boolean hasAudioPermission() {
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
        showToast("Transcription copied", Toast.LENGTH_SHORT);
    }

    private void updateButtons() {
        micButton.setText(recording ? R.string.button_stop : R.string.button_start);
        copyButton.setEnabled(!recording && !latestTranscript.trim().isEmpty());
        newButton.setEnabled(!recording);
        if (selectButton != null && localStore != null) {
            selectButton.setEnabled(!recording && !localStore.getNonEmptySlots().isEmpty());
        }
        if (deleteButton != null && localStore != null) {
            deleteButton.setEnabled(!recording && !localStore.getActiveText().trim().isEmpty());
        }
    }

    private void showToast(String message, int duration) {
        if (toastOverlay != null && toastOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) toastOverlay.getParent()).removeView(toastOverlay);
        }

        final TextView toastView = new TextView(this);
        toastView.setText(message);
        toastView.setTextColor(Color.WHITE);
        toastView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        toastView.setGravity(Gravity.CENTER);
        toastView.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(48));
        toastView.setPadding(dp(16), dp(10), dp(16), dp(10));

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(37, 99, 235));
        background.setCornerRadius(dp(20));
        toastView.setBackground(background);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        params.setMargins(dp(24), 0, dp(24), 0);

        toastOverlay = toastView;
        addContentView(toastView, params);

        int displayMillis = duration == Toast.LENGTH_LONG ? 3500 : 2000;
        toastView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (toastOverlay == toastView && toastView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) toastView.getParent()).removeView(toastView);
                    toastOverlay = null;
                }
            }
        }, displayMillis);
    }

    private String previewFor(String text) {
        if (text == null) return "";
        // collapse whitespace and newlines into single spaces
        String oneLine = text.replaceAll("\\s+", " ").trim();
        // limit characters to keep the dialog tidy
        int max = 44;
        if (oneLine.length() <= max) return oneLine;
        int cut = oneLine.lastIndexOf(' ', max);
        if (cut < max / 2) {
            cut = max;
        }
        return oneLine.substring(0, cut).trim() + "…";
    }

    private String selectLabelFor(int slot, String text) {
        long createdAt = localStore.getCreatedAtForSlot(slot);
        String timestamp = createdAt > 0L
                ? new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(new Date(createdAt))
                : "Saved transcription";
        return timestamp + "\n" + previewFor(text);
    }

    private void scrollTranscriptToBottom() {
        if (transcriptContainer == null) {
            return;
        }
        transcriptContainer.post(new Runnable() {
            @Override
            public void run() {
                transcriptContainer.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
