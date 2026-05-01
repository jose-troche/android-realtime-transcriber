package com.example.realtimetranscriber;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class TranscriptionStore {
    private static final String PREFS = "transcriptions";
    private static final String KEY_ACTIVE_SLOT = "active_slot";
    private static final String KEY_NEXT_SLOT = "next_slot";
    private static final String KEY_SLOT_PREFIX = "slot_";
    private static final int MAX_TRANSCRIPTIONS = 10;

    private final SharedPreferences preferences;

    TranscriptionStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized String getActiveText() {
        int slot = getOrCreateActiveSlot();
        return preferences.getString(slotKey(slot), "");
    }

    synchronized void updateActiveText(String text) {
        int slot = getOrCreateActiveSlot();
        preferences.edit().putString(slotKey(slot), text).apply();
    }

    synchronized void startNewTranscription() {
        int slot = preferences.getInt(KEY_NEXT_SLOT, 0);
        int nextSlot = (slot + 1) % MAX_TRANSCRIPTIONS;
        preferences.edit()
                .putInt(KEY_ACTIVE_SLOT, slot)
                .putInt(KEY_NEXT_SLOT, nextSlot)
                .putString(slotKey(slot), "")
                .apply();
    }

    synchronized List<String> getStoredTranscriptions() {
        ArrayList<String> transcriptions = new ArrayList<>();
        for (int i = 0; i < MAX_TRANSCRIPTIONS; i++) {
            String text = preferences.getString(slotKey(i), "");
            if (text != null && !text.trim().isEmpty()) {
                transcriptions.add(text);
            }
        }
        return transcriptions;
    }

    /**
     * Return a list of slot indices that contain non-empty transcriptions.
     */
    synchronized List<Integer> getNonEmptySlots() {
        ArrayList<Integer> slots = new ArrayList<>();
        for (int i = 0; i < MAX_TRANSCRIPTIONS; i++) {
            String text = preferences.getString(slotKey(i), "");
            if (text != null && !text.trim().isEmpty()) {
                slots.add(i);
            }
        }
        return slots;
    }

    synchronized String getTextForSlot(int slot) {
        return preferences.getString(slotKey(slot), "");
    }

    synchronized void setActiveSlot(int slot) {
        if (slot < 0 || slot >= MAX_TRANSCRIPTIONS) return;
        preferences.edit().putInt(KEY_ACTIVE_SLOT, slot).apply();
    }

    private int getOrCreateActiveSlot() {
        int activeSlot = preferences.getInt(KEY_ACTIVE_SLOT, -1);
        if (activeSlot >= 0) {
            return activeSlot;
        }
        preferences.edit()
                .putInt(KEY_ACTIVE_SLOT, 0)
                .putInt(KEY_NEXT_SLOT, 1)
                .putString(slotKey(0), "")
                .apply();
        return 0;
    }

    synchronized int getActiveSlot() {
        return preferences.getInt(KEY_ACTIVE_SLOT, 0);
    }

    synchronized void deleteActiveSlot() {
        int active = getOrCreateActiveSlot();
        // Clear the active slot
        preferences.edit().putString(slotKey(active), "").apply();
        // Find next non-empty slot (forward wrap). If found, make it active.
        for (int i = 1; i < MAX_TRANSCRIPTIONS; i++) {
            int idx = (active + i) % MAX_TRANSCRIPTIONS;
            String text = preferences.getString(slotKey(idx), "");
            if (text != null && !text.trim().isEmpty()) {
                preferences.edit().putInt(KEY_ACTIVE_SLOT, idx).apply();
                return;
            }
        }
        // No non-empty slot found — reset active slot to 0 and ensure it's empty
        preferences.edit().putInt(KEY_ACTIVE_SLOT, 0).putString(slotKey(0), "").apply();
    }

    private static String slotKey(int slot) {
        return KEY_SLOT_PREFIX + slot;
    }
}
