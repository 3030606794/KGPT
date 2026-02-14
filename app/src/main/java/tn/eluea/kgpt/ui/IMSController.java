/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * This file is part of KGPT.
 * Based on original code from KeyboardGPT by Mino260806.
 * Original: https://github.com/Mino260806/KeyboardGPT
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.List;

import tn.eluea.kgpt.BuildConfig;
import tn.eluea.kgpt.listener.InputEventListener;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import tn.eluea.kgpt.util.Logger;
import tn.eluea.kgpt.BuildConfig;

public class IMSController {
    private static final long INPUT_LOCK_TIMEOUT_MS = 15000; // 15 seconds timeout (reduced from 60s)

    private InputMethodService ims = null;
    private String typedText = "";
    private int cursor = 0;
    private volatile boolean inputNotify = false;
    private volatile boolean inputLock = false;
    private volatile long inputLockStartTime = 0;

    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());

    // Deferred commit/delete when InputConnection is temporarily null (e.g. IME resets during network calls)
    private static final long DEFERRED_TIMEOUT_MS = 12000; // 12 seconds
    private static final long DEFERRED_RETRY_DELAY_MS = 120; // ms

    private final Handler deferredHandler = new Handler(Looper.getMainLooper());

    // Input-event based updates (for IMEs that override onUpdateSelection without calling super)
    // We debounce updates triggered from InputConnection hooks (commitText / composing / delete) to avoid overhead.
    private final Handler inputEventHandler = new Handler(Looper.getMainLooper());
    private volatile boolean inputEventScheduled = false;
    private volatile InputConnection lastInputEventIC = null;
    private static final int INPUT_EVENT_MAX_BEFORE = 8192;
    private static final long INPUT_EVENT_DEBOUNCE_MS = 20;

    // Shadow buffer: supports editors that return null for getTextBeforeCursor/getExtractedText.
    private final StringBuilder shadow = new StringBuilder();
    private boolean composingActive = false;
    private int composingLen = 0;
    private static final int SHADOW_MAX = 2048;

    // Shadow buffer belongs to a specific target app. Without this, text typed in KGPT's own
    // settings dialogs (or another app) may leak into the next app and break trigger parsing.
    private String shadowPackage = null;


    private final StringBuilder pendingCommitBuffer = new StringBuilder();
    private int pendingDeleteBefore = 0;
    private int pendingDeleteAfter = 0;
    private boolean pendingFinishComposing = false;
    private boolean deferredScheduled = false;
    private long deferredStartTimeMs = 0;

    private final Runnable deferredRunnable = new Runnable() {
        @Override
        public void run() {
            deferredScheduled = false;

            if (!hasPending()) {
                deferredStartTimeMs = 0;
                return;
            }

            long now = System.currentTimeMillis();
            if (deferredStartTimeMs == 0) {
                deferredStartTimeMs = now;
            }
            if (now - deferredStartTimeMs > DEFERRED_TIMEOUT_MS) {
                // Timed out - copy the response to clipboard as a fallback
                fallbackToClipboardAndToast();
                clearDeferred();
                return;
            }

            boolean ok = true;

            if (pendingFinishComposing) {
                boolean flushOk = tryFlush();
                if (flushOk) {
                    pendingFinishComposing = false;
                }
                ok = ok && flushOk;
            }

            if (pendingDeleteBefore > 0 || pendingDeleteAfter > 0) {
    boolean delOk;
    if (pendingDeleteAfter > 0) {
        delOk = tryDeleteSurrounding(pendingDeleteBefore, pendingDeleteAfter);
    } else {
        delOk = tryDelete(pendingDeleteBefore);
    }
    if (delOk) {
        pendingDeleteBefore = 0;
        pendingDeleteAfter = 0;
    }
    ok = ok && delOk;
}

            if (pendingCommitBuffer.length() > 0) {
                String toCommit = pendingCommitBuffer.toString();
                boolean comOk = tryCommit(toCommit);
                if (comOk) {
                    pendingCommitBuffer.setLength(0);
                }
                ok = ok && comOk;
            }

            if (!ok) {
                scheduleDeferred();
            } else if (!hasPending()) {
                deferredStartTimeMs = 0;
            }
        }
    };

    private final Runnable lockTimeoutRunnable = () -> {
        if (inputLock) {
            // Force unlock after timeout
            tn.eluea.kgpt.util.Logger.log("Input lock timeout - forcing unlock");
            inputLock = false;
            inputNotify = false;
            inputLockStartTime = 0;
        }
    };

    private List<InputEventListener> mListeners = new ArrayList<>();

    public IMSController() {
    }

    public static IMSController getInstance() {
        return UiInteractor.getInstance().getIMSController();
    }

    public void onUpdateSelection(int oldSelStart,
            int oldSelEnd,
            int newSelStart,
            int newSelEnd,
            int candidatesStart,
            int candidatesEnd) {
        if (inputNotify) {
            return;
        }
        if (ims == null)
            return;

        InputConnection ic = ims.getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        // Primary path: prefer ExtractedText (full buffer when supported by the editor)
        try {
            ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (extractedText != null && extractedText.text != null) {
                typedText = extractedText.text.toString();
                int selEnd = extractedText.selectionEnd;
                int candidateCursor = (selEnd >= 0 ? selEnd : newSelEnd);
                cursor = Math.max(0, Math.min(candidateCursor, typedText.length()));
                notifyTextUpdate();
                return;
            }
        } catch (Throwable ignored) {
        }

        // Fallback: some custom editors return null for getExtractedText().
        // Build a local buffer around the cursor using getTextBefore/AfterCursor so triggers still work.
        try {
            final int MAX_BEFORE = 8192;
            final int MAX_AFTER = 1024;

            CharSequence before = ic.getTextBeforeCursor(MAX_BEFORE, 0);
            CharSequence after = ic.getTextAfterCursor(MAX_AFTER, 0);

            if (before == null && after == null) {
                return;
            }

            StringBuilder sb = new StringBuilder();
            if (before != null) sb.append(before);
            int localCursor = sb.length();
            if (after != null) sb.append(after);

            typedText = sb.toString();
            cursor = localCursor;
            notifyTextUpdate();
        } catch (Throwable ignored) {
        }
    }


    /**
     * Fallback update path triggered directly from InputConnection hooks.
     * Some keyboards override InputMethodService.onUpdateSelection() and don't call super(),
     * which means our onUpdateSelection hook never runs. In that case we still want keyword
     * triggers (AI 触发器) to work reliably.
     */
    public void requestTextUpdateFromInputEvent(InputConnection ic) {
        if (inputNotify) return;
        if (ims == null) return;
        try {
			android.view.inputmethod.EditorInfo ei = ims.getCurrentInputEditorInfo();
			String pkg = (ei != null) ? ei.packageName : null;
			if (pkg != null) {
				if (tn.eluea.kgpt.BuildConfig.APPLICATION_ID.equals(pkg)) {
					// We're typing inside KGPT's own UI. Clear shadow so it won't leak into other apps.
					shadowPackage = pkg;
					resetShadow();
					return;
				}
				ensureShadowForPackage(pkg);
			}
        } catch (Throwable ignored) {}
        if (ic == null) {
            try {
                ic = ims.getCurrentInputConnection();
            } catch (Throwable ignored) {}
        }
        if (ic == null) return;

        lastInputEventIC = ic;

        if (inputEventScheduled) return;
        inputEventScheduled = true;

        inputEventHandler.postDelayed(() -> {
            inputEventScheduled = false;
            InputConnection current = lastInputEventIC;
            if (current == null) {
                try {
                    current = ims.getCurrentInputConnection();
                } catch (Throwable ignored) {}
            }
            if (current == null) return;

            // Prefer a local buffer around the cursor: it works in many editors even when getExtractedText() returns null.
            // IMPORTANT: include after-cursor text when possible so UI layers (e.g., Quick Jump menu) can read the
            // "current line" even if the cursor is placed at the beginning of the word/line.
            try {
                final int MAX_BEFORE = INPUT_EVENT_MAX_BEFORE;
                final int MAX_AFTER = 2048;

                CharSequence before = current.getTextBeforeCursor(MAX_BEFORE, 0);
                CharSequence after = current.getTextAfterCursor(MAX_AFTER, 0);

                if (before != null || after != null) {
                    String b = before != null ? before.toString() : "";
                    String a = after != null ? after.toString() : "";
                    typedText = b + a;
                    cursor = b.length();
                    notifyTextUpdate();
                    return;
                }
            } catch (Throwable ignored) {}

            // Fallback to ExtractedText (some editors only support this)
            try {
                ExtractedText extractedText = current.getExtractedText(new ExtractedTextRequest(), 0);
                if (extractedText != null && extractedText.text != null) {
                    typedText = extractedText.text.toString();
                    int selEnd = extractedText.selectionEnd;
                    int candidateCursor = (selEnd >= 0 ? selEnd : typedText.length());
                    cursor = Math.max(0, Math.min(candidateCursor, typedText.length()));
                    notifyTextUpdate();
                }
            } catch (Throwable ignored) {}


// Fallback to shadow buffer: some editors return null for both methods.
if (shadow.length() > 0) {
    typedText = shadow.toString();
    cursor = typedText.length();
    notifyTextUpdate();
}
        }, INPUT_EVENT_DEBOUNCE_MS);
    }



private void trimShadowIfNeeded() {
    if (shadow.length() > SHADOW_MAX) {
        shadow.delete(0, shadow.length() - SHADOW_MAX);
    }
}

    private String getCurrentTargetPackageName() {
        try {
            if (ims == null) return null;
            EditorInfo ei = ims.getCurrentInputEditorInfo();
            return ei != null ? ei.packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isKgptPackage(String pkg) {
        return pkg != null && BuildConfig.APPLICATION_ID.equals(pkg);
    }

    private void ensureShadowForPackage(String pkg) {
        if (pkg == null) return;
        if (shadowPackage == null) {
            shadowPackage = pkg;
            return;
        }
        if (!pkg.equals(shadowPackage)) {
            resetShadow();
            shadowPackage = pkg;
        }
    }

    private void shadowDeleteFromEnd(int count) {
        if (count <= 0) return;
        try {
            String pkg = getCurrentTargetPackageName();
            ensureShadowForPackage(pkg);

            int len = shadow.length();
            if (len <= 0) return;

            int start = Math.max(0, len - count);
            shadow.delete(start, len);
        } catch (Exception ignored) {
        }
    }

/**
 * Update shadow buffer from InputConnection events. These events are available even when editors
 * deny access to getTextBeforeCursor()/getExtractedText().
 * Note: these methods DO NOT notify listeners directly. Notification is done in requestTextUpdateFromInputEvent()
 * (debounced) to avoid duplicate trigger firing.
 */
public void onInputEventText(CharSequence cs, boolean composing) {
    if (inputNotify) return;
    if (cs == null) return;
    String s = cs.toString();
    if (s.isEmpty()) return;

	String pkg = getCurrentTargetPackageName();
	if (isKgptPackage(pkg)) {
		shadowPackage = pkg;
		resetShadow();
		return;
	}
	ensureShadowForPackage(pkg);

    // If we were composing, replace the previous composing tail with the new one
    if (composingActive && composingLen > 0 && shadow.length() >= composingLen) {
        int start = Math.max(0, shadow.length() - composingLen);
        shadow.delete(start, shadow.length());
        composingLen = 0;
    }

    shadow.append(s);
    trimShadowIfNeeded();

    if (composing) {
        composingActive = true;
        composingLen = s.length();
    } else {
        composingActive = false;
        composingLen = 0;
    }
}

public void onInputEventFinishComposing() {
    if (inputNotify) return;

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    composingActive = false;
    composingLen = 0;
}

public void onInputEventDelete(int before, int after) {
    if (inputNotify) return;
    before = Math.max(0, before);
    after = Math.max(0, after);

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    if (before > 0 && shadow.length() > 0) {
        int start = Math.max(0, shadow.length() - before);
        shadow.delete(start, shadow.length());
    }
    // We don't track cursor-after deletions accurately in tail-mode; ignore 'after'.

    composingActive = false;
    composingLen = 0;
}

public void onInputEventReplace(int start, int end, CharSequence cs) {
    if (inputNotify) return;

    String pkg = getCurrentTargetPackageName();
    if (isKgptPackage(pkg)) {
        shadowPackage = pkg;
        resetShadow();
        return;
    }
    ensureShadowForPackage(pkg);

    composingActive = false;
    composingLen = 0;
    if (cs != null) {
        String s = cs.toString();
        if (!s.isEmpty()) {
            shadow.append(s);
            trimShadowIfNeeded();
        }
    }
}

    

public void resetShadow() {
    try { shadow.setLength(0); } catch (Throwable ignored) {}
    composingActive = false;
    composingLen = 0;
}

/**
 * Snapshot getters for UI layers (e.g., Quick Jump menu) that need the latest input keyword.
 * These are best-effort and may lag slightly depending on editor callbacks.
 */
public String getTypedTextSnapshot() {
    try { return typedText != null ? typedText : ""; } catch (Throwable t) { return ""; }
}

public int getCursorSnapshot() {
    try { return cursor; } catch (Throwable t) { return 0; }
}


public void addListener(InputEventListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(InputEventListener listener) {
        mListeners.remove(listener);
    }

    private void notifyTextUpdate() {
        for (InputEventListener listener : mListeners) {
            listener.onTextUpdate(typedText, cursor);
        }
    }

    public void registerService(InputMethodService ims) {
        this.ims = ims;
    }

    public void unregisterService(InputMethodService ims) {
        this.ims = null;
        try { shadow.setLength(0); } catch (Throwable ignored) {}
        composingActive = false;
        composingLen = 0;
    }

    public void delete(int count) {
        if (count <= 0) {
            return;
        }
        if (tryDelete(count)) {
            shadowDeleteFromEnd(count);
            return;
        }
        // Queue delete until InputConnection becomes available again
        pendingDeleteBefore += count;
        scheduleDeferred();
    }


    public void deleteSurrounding(int before, int after) {
        if (before <= 0 && after <= 0) {
            return;
        }
        before = Math.max(0, before);
        after = Math.max(0, after);

        if (tryDeleteSurrounding(before, after)) {
            return;
        }

        // Queue delete until InputConnection becomes available again
        // Avoid accumulating relative deletes across cursor moves (can delete wrong text)
        pendingDeleteBefore = Math.max(pendingDeleteBefore, before);
        pendingDeleteAfter = Math.max(pendingDeleteAfter, after);
        scheduleDeferred();
    }

    /**
     * Delete a text range using absolute offsets (safer than relative deleteSurrounding in some editors).
     * This will NOT delete the line break if you pass end before the newline character.
     *
     * @return true if deleted immediately, false if InputConnection is unavailable.
     */
    public boolean deleteRange(int start, int end) {
        start = Math.max(0, start);
        end = Math.max(0, end);
        if (end <= start) return true;

        InputConnection ic = getIC();
        if (ic == null) return false;

        try {
            ic.beginBatchEdit();
            boolean selOk = true;
            try {
                selOk = ic.setSelection(start, end);
            } catch (Throwable ignored) {
            }
            if (!selOk) {
                try { ic.endBatchEdit(); } catch (Throwable ignored) {}
                return false;
            }
            ic.commitText("", 1);
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            return true;
        } catch (Throwable t) {
            try { ic.endBatchEdit(); } catch (Throwable ignored) {}
            Logger.error("IMS deleteRange failed: " + t.getMessage());
            return false;
        }
    }





    public void commit(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (tryCommit(text)) {
            return;
        }
        // Queue commit until InputConnection becomes available again
        pendingCommitBuffer.append(text);
        scheduleDeferred();
    }

    /**
     * Attempt an immediate commit to the current InputConnection.
     * Returns true if the text was committed, false if InputConnection is unavailable.
     *
     * This is used by the floating UI (e.g., AI clipboard) where we must NOT queue
     * a deferred commit when the keyboard is not active.
     */
    public boolean commitToInputNow(String text) {
        if (text == null || text.isEmpty()) return true;
        try {
            return tryCommit(text);
        } catch (Throwable t) {
            return false;
        }
    }

    public void stopNotifyInput() {
        inputNotify = true;
    }

    public void startNotifyInput() {
        inputNotify = false;
    }

    public void flush() {
        if (tryFlush()) {
            return;
        }
        pendingFinishComposing = true;
        scheduleDeferred();
    }

    public boolean isInputLocked() {
        // Auto-unlock if timeout exceeded
        if (inputLock && inputLockStartTime > 0) {
            long elapsed = System.currentTimeMillis() - inputLockStartTime;
            if (elapsed > INPUT_LOCK_TIMEOUT_MS) {
                inputLock = false;
                inputNotify = false;
                inputLockStartTime = 0;
                timeoutHandler.removeCallbacks(lockTimeoutRunnable);
            }
        }
        return inputLock;
    }

    public void startInputLock() {
        inputLock = true;
        inputLockStartTime = System.currentTimeMillis();
        // Schedule timeout
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
        timeoutHandler.postDelayed(lockTimeoutRunnable, INPUT_LOCK_TIMEOUT_MS);
    }

    public void endInputLock() {
        inputLock = false;
        inputLockStartTime = 0;
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
    }

    /**
     * Force reset the input lock state. Use this to recover from stuck states.
     */
    public void forceResetLock() {
        inputLock = false;
        inputNotify = false;
        inputLockStartTime = 0;
        timeoutHandler.removeCallbacks(lockTimeoutRunnable);
    }
    private boolean hasPending() {
        return pendingFinishComposing || pendingDeleteBefore > 0 || pendingDeleteAfter > 0 || pendingCommitBuffer.length() > 0;
    }

    private void clearDeferred() {
        pendingFinishComposing = false;
        pendingDeleteBefore = 0;
        pendingDeleteAfter = 0;
        pendingCommitBuffer.setLength(0);
        deferredStartTimeMs = 0;
        deferredHandler.removeCallbacks(deferredRunnable);
        deferredScheduled = false;
    }

    private void scheduleDeferred() {
        if (!hasPending()) {
            return;
        }
        if (!deferredScheduled) {
            deferredScheduled = true;
            deferredHandler.postDelayed(deferredRunnable, DEFERRED_RETRY_DELAY_MS);
        }
    }

    private android.view.inputmethod.InputConnection getIC() {
        if (ims == null) {
            return null;
        }
        try {
            return ims.getCurrentInputConnection();
        } catch (Throwable t) {
            Logger.error("getCurrentInputConnection failed: " + t.getMessage());
            return null;
        }
    }

    private boolean tryDelete(int count) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }


        try {
            ic.deleteSurroundingText(count, 0);
            return true;
        } catch (Throwable t) {
            Logger.error("IMS delete failed: " + t.getMessage());
            return false;
        }
    }

    private boolean tryDeleteSurrounding(int before, int after) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        try {
            ic.deleteSurroundingText(Math.max(0, before), Math.max(0, after));
            return true;
        } catch (Throwable t) {
            Logger.error("IMS deleteSurrounding failed: " + t.getMessage());
            return false;
        }
    }


    private boolean tryCommit(String text) {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        try {
            ic.commitText(text, 1);
            return true;
        } catch (Throwable t) {
            Logger.error("IMS commit failed: " + t.getMessage());
            return false;
        }
    }

    private boolean tryFlush() {
        android.view.inputmethod.InputConnection ic = getIC();
        if (ic == null) {
            return false;
        }
        try {
            ic.finishComposingText();
            return true;
        } catch (Throwable t) {
            Logger.error("IMS flush failed: " + t.getMessage());
            return false;
        }
    }

    private void fallbackToClipboardAndToast() {
        if (pendingCommitBuffer.length() == 0) {
            return;
        }
        try {
            Context ctx = UiInteractor.getInstance().getContext();
            if (ctx == null) {
                return;
            }
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("KGPT", pendingCommitBuffer.toString()));
            }
            UiInteractor.getInstance().toastLong("已复制 AI 结果到剪贴板（无法自动写入输入框，请手动粘贴）");
        } catch (Throwable t) {
            Logger.error("Clipboard fallback failed: " + t.getMessage());
        }
    }

}
