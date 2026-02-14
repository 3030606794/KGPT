package tn.eluea.kgpt.features.floatingball;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import tn.eluea.kgpt.provider.ConfigClient;
import tn.eluea.kgpt.ui.UiInteractor;

/**
 * Receives IME visibility/height updates from the hooked keyboard process.
 *
 * Why a manifest receiver?
 * - Dynamic receivers (registered by {@link FloatingBallService}) won't be active if the app process
 *   was killed.
 * - On Android 13+ dynamic receivers must specify exported/not-exported flags; OEM ROMs differ.
 *
 * This receiver caches IME metrics into {@link tn.eluea.kgpt.provider.ConfigProvider} so the
 * floating pad can always follow the keyboard height.
 */
public class ImeMetricsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        final String action = intent.getAction();
        if (action == null || !UiInteractor.ACTION_FB_IME_METRICS.equals(action)) return;

                // NOTE: accept IME metrics broadcasts from any app.
        // Rationale: some users enable KGPT only for target apps (e.g. WeChat) and not for the IME.
        // In that case, metrics are sent from the host app process rather than the keyboard process.
        // This receiver caches the latest metrics for the floating pad.
        boolean visible = false;
        int top = -1;
        int h = -1;
        try { visible = intent.getBooleanExtra(UiInteractor.EXTRA_IME_VISIBLE, false); } catch (Throwable ignored) {}
        try { top = intent.getIntExtra(UiInteractor.EXTRA_IME_TOP, -1); } catch (Throwable ignored) {}
        try { h = intent.getIntExtra(UiInteractor.EXTRA_IME_HEIGHT, -1); } catch (Throwable ignored) {}

        try {
            ConfigClient c = new ConfigClient(context.getApplicationContext());
            c.putBoolean(FloatingBallKeys.KEY_PAD_IME_VISIBLE, visible);
            if (visible) {
                if (top > 0) c.putInt(FloatingBallKeys.KEY_PAD_IME_TOP, top);
                if (h > 0) c.putInt(FloatingBallKeys.KEY_PAD_IME_HEIGHT, h);
            }
        } catch (Throwable ignored) {}
    }
}
