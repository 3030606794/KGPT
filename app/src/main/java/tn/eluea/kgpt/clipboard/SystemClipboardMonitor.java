/*
 * KGPT - AI in your keyboard
 *
 * SystemClipboardMonitor:
 * A lightweight listener running in the KGPT app process to capture **system**
 * clipboard changes and append them into "AI剪贴板" history.
 *
 * Notes:
 * - This does NOT require Xposed/LSPosed scope to System Framework.
 * - Some Android versions restrict background clipboard access. On those ROMs,
 *   this will work best while KGPT is in the foreground (e.g. overlay opened).
 */
package tn.eluea.kgpt.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import tn.eluea.kgpt.util.Logger;

public final class SystemClipboardMonitor {

    private static final String TAG = "KGPT_SystemClipboard";

    private static volatile boolean sInstalled = false;
    private static ClipboardManager sCm;
    private static ClipboardManager.OnPrimaryClipChangedListener sListener;

    // Dedup (fast)
    private static volatile String sLastText = null;
    private static volatile long sLastTime = 0L;

    private SystemClipboardMonitor() {}

    /**
     * Install the system clipboard listener in the KGPT app process.
     * Safe to call multiple times.
     */
    public static synchronized void ensureStarted(Context context) {
        if (sInstalled) return;
        if (context == null) return;

        final Context appCtx = context.getApplicationContext();
        try {
            sCm = (ClipboardManager) appCtx.getSystemService(Context.CLIPBOARD_SERVICE);
        } catch (Throwable t) {
            sCm = null;
        }
        if (sCm == null) return;

        sListener = new ClipboardManager.OnPrimaryClipChangedListener() {
            @Override
            public void onPrimaryClipChanged() {
                try {
                    ClipData clip = sCm.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) return;
                    ClipData.Item item = clip.getItemAt(0);
                    if (item == null) return;

                    CharSequence cs = item.getText();
                    if (cs == null) cs = item.coerceToText(appCtx);
                    if (cs == null) return;

                    String text = cs.toString();
                    if (text == null) return;
                    text = text.trim();
                    if (text.isEmpty()) return;

                    long now = System.currentTimeMillis();
                    String last = sLastText;
                    long lastT = sLastTime;
                    if (last != null && last.equals(text) && (now - lastT) < 800) return;

                    sLastText = text;
                    sLastTime = now;

                    AIClipboardStore.append(appCtx, text);
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            sCm.addPrimaryClipChangedListener(sListener);
            sInstalled = true;
            Logger.log(TAG + ": installed");
        } catch (Throwable t) {
            sInstalled = false;
            sListener = null;
        }

        // Import current clipboard once (if available) so the list/count is fresh.
        try {
            if (sInstalled && sListener != null) {
                sListener.onPrimaryClipChanged();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Remove the listener. Usually not needed.
     */
    public static synchronized void stop(Context context) {
        try {
            if (sCm != null && sListener != null) {
                sCm.removePrimaryClipChangedListener(sListener);
            }
        } catch (Throwable ignored) {
        }
        sInstalled = false;
        sListener = null;
        sCm = null;
    }
}
