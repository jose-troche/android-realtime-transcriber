package com.example.realtimetranscriber;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class TranscriptionStore {
    private static final String PREFS = "transcriptions";
    private static final String KEY_ACTIVE_SLOT = "active_slot";
    private static final String KEY_NEXT_SLOT = "next_slot";
    private static final String KEY_SLOT_PREFIX = "slot_";
    private static final String KEY_SLOT_CREATED_AT_PREFIX = "slot_created_at_";
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
        int slot = findSlotForNewTranscription();
        int nextSlot = (slot + 1) % MAX_TRANSCRIPTIONS;
        preferences.edit()
                .putInt(KEY_ACTIVE_SLOT, slot)
                .putInt(KEY_NEXT_SLOT, nextSlot)
                .putString(slotKey(slot), "")
                .putLong(slotCreatedAtKey(slot), System.currentTimeMillis())
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

    synchronized List<Integer> getNonEmptySlotsChronological() {
        ArrayList<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < MAX_TRANSCRIPTIONS; slot++) {
            String text = preferences.getString(slotKey(slot), "");
            if (text != null && !text.trim().isEmpty()) {
                slots.add(slot);
            }
        }
        Collections.sort(slots, new Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                long leftCreatedAt = getCreatedAtForSlot(left);
                long rightCreatedAt = getCreatedAtForSlot(right);
                if (leftCreatedAt < rightCreatedAt) return -1;
                if (leftCreatedAt > rightCreatedAt) return 1;
                return left - right;
            }
        });
        return slots;
    }

    synchronized String getTextForSlot(int slot) {
        return preferences.getString(slotKey(slot), "");
    }

    synchronized long getCreatedAtForSlot(int slot) {
        return preferences.getLong(slotCreatedAtKey(slot), 0L);
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
                .putLong(slotCreatedAtKey(0), System.currentTimeMillis())
                .apply();
        return 0;
    }

    synchronized int getActiveSlot() {
        return preferences.getInt(KEY_ACTIVE_SLOT, 0);
    }

    private int findSlotForNewTranscription() {
        int activeSlot = preferences.getInt(KEY_ACTIVE_SLOT, -1);
        if (isValidSlot(activeSlot) && isSlotEmpty(activeSlot)) {
            return activeSlot;
        }

        int nextSlot = preferences.getInt(KEY_NEXT_SLOT, 0);
        for (int offset = 0; offset < MAX_TRANSCRIPTIONS; offset++) {
            int slot = (nextSlot + offset) % MAX_TRANSCRIPTIONS;
            if (isSlotEmpty(slot)) {
                return slot;
            }
        }

        return findOldestSlotExcept(activeSlot);
    }

    private int findOldestSlotExcept(int excludedSlot) {
        int fallbackSlot = isValidSlot(excludedSlot) ? (excludedSlot + 1) % MAX_TRANSCRIPTIONS : 0;
        int oldestSlot = fallbackSlot;
        long oldestCreatedAt = Long.MAX_VALUE;
        for (int slot = 0; slot < MAX_TRANSCRIPTIONS; slot++) {
            if (slot == excludedSlot) {
                continue;
            }
            long createdAt = getCreatedAtForSlot(slot);
            if (createdAt <= 0L) {
                return slot;
            }
            if (createdAt < oldestCreatedAt) {
                oldestCreatedAt = createdAt;
                oldestSlot = slot;
            }
        }
        return oldestSlot;
    }

    private boolean isSlotEmpty(int slot) {
        if (!isValidSlot(slot)) {
            return false;
        }
        String text = preferences.getString(slotKey(slot), "");
        return text == null || text.trim().isEmpty();
    }

    private static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < MAX_TRANSCRIPTIONS;
    }

    synchronized void deleteActiveSlot() {
        int active = getOrCreateActiveSlot();
        // Clear the active slot
        preferences.edit()
                .putString(slotKey(active), "")
                .remove(slotCreatedAtKey(active))
                .apply();
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
        preferences.edit()
                .putInt(KEY_ACTIVE_SLOT, 0)
                .putString(slotKey(0), "")
                .putLong(slotCreatedAtKey(0), System.currentTimeMillis())
                .apply();
    }

    private static String slotKey(int slot) {
        return KEY_SLOT_PREFIX + slot;
    }

    private static String slotCreatedAtKey(int slot) {
        return KEY_SLOT_CREATED_AT_PREFIX + slot;
    }
}
