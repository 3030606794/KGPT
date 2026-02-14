package tn.eluea.kgpt.features.floatingball;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import tn.eluea.kgpt.R;
import tn.eluea.kgpt.core.ui.DialogActivity;
import tn.eluea.kgpt.core.ui.dialog.DialogType;
import tn.eluea.kgpt.provider.ConfigClient;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.ui.lab.floatingball.FloatingBallActivity;
import tn.eluea.kgpt.ui.main.MainActivity;

/**
 * Experimental floating entrance (v4): a single fixed rectangle "pad".
 *
 * Gestures:
 * - Tap: open AI chat input dialog
 * - Long press: copy the entire current input field
 * - Swipe Up: open invocation menu (ChooseSubModel)
 * - Swipe Down: open floating entrance settings (Lab)
 * - Swipe Left: open AI Commands page
 * - Swipe Right: open AI Triggers page
 *
 * Position (X/Y) and size (W/H) are controlled from settings.
 */
public class FloatingBallService extends Service {

    public static void sync(Context ctx) {
        try {
            ConfigClient c = new ConfigClient(ctx);
            boolean enabled = c.getBoolean(FloatingBallKeys.KEY_PAD_ENABLED, false);
            // Migration: if user had old balls enabled, keep the feature running.
            boolean legacyAi = c.getBoolean(FloatingBallKeys.KEY_AI_BALL_ENABLED, false);
            boolean legacyCopy = c.getBoolean(FloatingBallKeys.KEY_COPY_BALL_ENABLED, false);
            boolean shouldRun = enabled || legacyAi || legacyCopy;

            Intent i = new Intent(ctx, FloatingBallService.class);
            if (shouldRun) {
                try {
                    ctx.startService(i);
                } catch (Throwable t) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ctx.startForegroundService(i);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                try {
                    ctx.stopService(i);
                } catch (Throwable ignored) {
                }
            }
            try { c.destroy(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private WindowManager wm;
    private ConfigClient client;

    private View padView;
    private WindowManager.LayoutParams padLp;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // IME visibility/metrics (sent from Xposed hook inside keyboard process)
    private volatile boolean imeVisible = false;
    private volatile int imeTop = -1;
    private volatile int imeHeight = -1;
    private BroadcastReceiver imeMetricsReceiver;
    private boolean imeReceiverRegistered = false;

    // Touch handling
    private float downX;
    private float downY;
    private long downTime;
    private boolean longPressTriggered;
    private boolean movedTooMuch;
    private Runnable longPressRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        client = new ConfigClient(getApplicationContext());

        // Live updates from settings.
        try {
            client.registerGlobalListener((k, v) -> {
                if (k == null) return;
                if (k.startsWith("floating_pad_") || k.startsWith("floating_ball_")) {
                    applyConfigAndRefresh();
                }
            });
        } catch (Throwable ignored) {
        }

        registerImeReceiverIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        applyConfigAndRefresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removePadIfNeeded();
        unregisterImeReceiverIfNeeded();
        try { if (client != null) client.destroy(); } catch (Throwable ignored) {}
        cancelLongPress();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void applyConfigAndRefresh() {
        boolean enabled = client.getBoolean(FloatingBallKeys.KEY_PAD_ENABLED, false);
        boolean legacyAi = client.getBoolean(FloatingBallKeys.KEY_AI_BALL_ENABLED, false);
        boolean legacyCopy = client.getBoolean(FloatingBallKeys.KEY_COPY_BALL_ENABLED, false);

        if (!enabled && !legacyAi && !legacyCopy) {
            stopSelf();
            return;
        }

        // Keyboard-follow behavior: show only while IME is visible.
        // IME visibility/metrics come from:
        // 1) runtime broadcast (when service is alive)
        // 2) cached metrics written by a manifest receiver (works even if the service was killed)
        boolean effectiveImeVisible = imeVisible || client.getBoolean(FloatingBallKeys.KEY_PAD_IME_VISIBLE, false);
        if (!effectiveImeVisible) {
            removePadIfNeeded();
            return;
        }

        // Merge metrics (prefer live values, fallback to cached)
        int effectiveImeTop = (imeTop > 0) ? imeTop : client.getInt(FloatingBallKeys.KEY_PAD_IME_TOP, -1);
        int effectiveImeHeight = (imeHeight > 0) ? imeHeight : client.getInt(FloatingBallKeys.KEY_PAD_IME_HEIGHT, -1);

        if (padView == null) {
            padView = buildPadView();
        }

        int wDp = clamp(client.getInt(FloatingBallKeys.KEY_PAD_W_DP, FloatingBallKeys.DEFAULT_PAD_W_DP), 72, 320);
        int hDp = clamp(client.getInt(FloatingBallKeys.KEY_PAD_H_DP, FloatingBallKeys.DEFAULT_PAD_H_DP), 32, 180);
        int wPx = dpToPx(wDp);
        int hPx = dpToPx(hDp);

        if (padLp == null) {
            padLp = createBaseLayoutParams(wPx, hPx);
        } else {
            padLp.width = wPx;
            padLp.height = hPx;
        }

        // Position based on normalized 0..1000.
        // X: screen-wide.
        // Y: within the keyboard window (0 = top of keyboard, 1000 = bottom of keyboard).
        int x1000 = client.getInt(FloatingBallKeys.KEY_PAD_X, FloatingBallKeys.POS_UNSET);
        int y1000 = client.getInt(FloatingBallKeys.KEY_PAD_Y, FloatingBallKeys.POS_UNSET);
        int[] pos = resolvePosInIme(x1000, y1000, wPx, hPx, effectiveImeTop, effectiveImeHeight);
        padLp.x = pos[0];
        padLp.y = pos[1];

        addOrUpdateView(padView, padLp);
    }

    private View buildPadView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundResource(R.drawable.bg_floating_pad_rect);
        root.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_ai_invocation_filled));
        // Avoid referencing theme attrs that may not exist in this build.
        // The drawable already matches the app's style; leave it as-is.
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dpToPx(22), dpToPx(22));
        ilp.gravity = Gravity.CENTER;
        root.addView(icon, ilp);

        root.setOnTouchListener((v, event) -> {
            handleTouch(event);
            return true;
        });

        return root;
    }

    private void handleTouch(MotionEvent event) {
        final float slop = dpToPxF(12);
        final float swipeThreshold = dpToPxF(52);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                downTime = System.currentTimeMillis();
                longPressTriggered = false;
                movedTooMuch = false;
                scheduleLongPress();
                break;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    movedTooMuch = true;
                    cancelLongPress();
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelLongPress();
                if (longPressTriggered) {
                    // already handled
                    break;
                }

                float upDx = event.getRawX() - downX;
                float upDy = event.getRawY() - downY;

                // Swipe detection (works even for slow swipes)
                if (Math.abs(upDx) > swipeThreshold || Math.abs(upDy) > swipeThreshold) {
                    if (Math.abs(upDx) > Math.abs(upDy)) {
                        if (upDx > 0) onSwipeRight(); else onSwipeLeft();
                    } else {
                        if (upDy > 0) onSwipeDown(); else onSwipeUp();
                    }
                } else {
                    // Tap
                    onTap();
                }
                break;
        }
    }

    private void scheduleLongPress() {
        cancelLongPress();
        longPressRunnable = () -> {
            longPressTriggered = true;
            onLongPress();
        };
        mainHandler.postDelayed(longPressRunnable, 520);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            try { mainHandler.removeCallbacks(longPressRunnable); } catch (Throwable ignored) {}
            longPressRunnable = null;
        }
    }

    private void onTap() {
        try {
            Intent i = new Intent(this, DialogActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(UiInteractor.EXTRA_DIALOG_TYPE, DialogType.ChatInput.name());
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void onLongPress() {
        // Copy full input content via keyboard hook.
        try {
            Intent i = new Intent(UiInteractor.ACTION_FB_COPY_ALL);
            sendBroadcast(i);
        } catch (Throwable ignored) {
        }
        toastSafe(getString(R.string.msg_copied));
    }

    private void onSwipeUp() {
        try {
            Intent i = new Intent(this, DialogActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(UiInteractor.EXTRA_DIALOG_TYPE, DialogType.ChooseSubModel.name());
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void onSwipeDown() {
        try {
            Intent i = new Intent(this, FloatingBallActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void onSwipeLeft() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(MainActivity.EXTRA_OPEN_AI_INVOCATION, true);
            i.putExtra(MainActivity.EXTRA_AI_INVOCATION_TAB, 0); // Commands
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void onSwipeRight() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra(MainActivity.EXTRA_OPEN_AI_INVOCATION, true);
            i.putExtra(MainActivity.EXTRA_AI_INVOCATION_TAB, 1); // Triggers
            startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private void toastSafe(String msg) {
        try { Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private WindowManager.LayoutParams createBaseLayoutParams(int wPx, int hPx) {
        int type;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            //noinspection deprecation
            type = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                wPx,
                hPx,
                type,
                flags,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void addOrUpdateView(View v, WindowManager.LayoutParams lp) {
        try {
            if (v.getParent() == null) {
                wm.addView(v, lp);
            } else {
                wm.updateViewLayout(v, lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private void removePadIfNeeded() {
        if (padView != null) {
            try {
                if (padView.getParent() != null) wm.removeView(padView);
            } catch (Throwable ignored) {
            }
        }
        padView = null;
        padLp = null;
    }

    private void registerImeReceiverIfNeeded() {
        if (imeReceiverRegistered) return;
        imeMetricsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!UiInteractor.ACTION_FB_IME_METRICS.equals(intent.getAction())) return;

                boolean vis = intent.getBooleanExtra(UiInteractor.EXTRA_IME_VISIBLE, false);
                imeVisible = vis;
                if (vis) {
                    imeTop = intent.getIntExtra(UiInteractor.EXTRA_IME_TOP, -1);
                    imeHeight = intent.getIntExtra(UiInteractor.EXTRA_IME_HEIGHT, -1);
                }

                // Refresh on main thread
                mainHandler.post(() -> {
                    try { applyConfigAndRefresh(); } catch (Throwable ignored) {}
                });
            }
        };

        try {
            IntentFilter f = new IntentFilter(UiInteractor.ACTION_FB_IME_METRICS);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(imeMetricsReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(imeMetricsReceiver, f);
            }
            imeReceiverRegistered = true;
        } catch (Throwable ignored) {
            imeReceiverRegistered = false;
            imeMetricsReceiver = null;
        }
    }

    private void unregisterImeReceiverIfNeeded() {
        if (!imeReceiverRegistered) return;
        try {
            unregisterReceiver(imeMetricsReceiver);
        } catch (Throwable ignored) {
        }
        imeMetricsReceiver = null;
        imeReceiverRegistered = false;
    }

    private int[] resolvePosInIme(int x1000, int y1000, int wPx, int hPx, int imeTopPx, int imeHeightPx) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        int availW = Math.max(0, screenW - wPx);
        int defX = (int) (availW * 0.80f);
        int x = (x1000 == FloatingBallKeys.POS_UNSET) ? defX : (int) (availW * (x1000 / 1000f));
        x = clamp(x, 0, availW);

        int top = imeTopPx;
        int height = imeHeightPx;
        if (top <= 0 && height > 0) top = screenH - height;
        if (height <= 0 && top > 0) height = screenH - top;

        if (top < 0 || height <= 0) {
            // Fallback to screen-based placement if metrics are missing.
            int[] p = resolvePos(x1000, y1000, wPx, hPx);
            return new int[] { p[0], p[1] };
        }

        // Place the pad ABOVE the keyboard so it is never covered by the IME window.
        // Note: TYPE_APPLICATION_OVERLAY is always below TYPE_INPUT_METHOD. If we position the overlay
        // inside the keyboard area, it will be hidden under the keyboard.
        int baseAboveKeyboard = top - hPx - dpToPx(6);
        if (baseAboveKeyboard < 0) baseAboveKeyboard = 0;

        // Y slider semantics (0..1000): how far to lift the pad upward from the keyboard top.
        // 0   => sits right on top of the keyboard
        // 1000=> lifted up by ~280dp
        int maxUp = dpToPx(280);
        int defYNorm = 200; // default lift (~56dp) to reduce chance of covering the host app's input row
        int yNorm = (y1000 == FloatingBallKeys.POS_UNSET) ? defYNorm : y1000;
        yNorm = clamp(yNorm, 0, 1000);

        int y = baseAboveKeyboard - (int) (maxUp * (yNorm / 1000f));
        y = clamp(y, 0, baseAboveKeyboard);
        return new int[] { x, y };
    }

    private int[] resolvePos(int x1000, int y1000, int wPx, int hPx) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;

        int availW = Math.max(0, screenW - wPx);
        int availH = Math.max(0, screenH - hPx);

        int defX = (int) (availW * 0.80f);
        int defY = (int) (availH * 0.55f);

        int x = (x1000 == FloatingBallKeys.POS_UNSET) ? defX : (int) (availW * (x1000 / 1000f));
        int y = (y1000 == FloatingBallKeys.POS_UNSET) ? defY : (int) (availH * (y1000 / 1000f));

        x = clamp(x, 0, availW);
        y = clamp(y, 0, availH);
        return new int[] { x, y };
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private float dpToPxF(int dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private int resolveThemeColor(int attrRes) {
        try {
            TypedValue tv = new TypedValue();
            if (getTheme() != null && getTheme().resolveAttribute(attrRes, tv, true)) {
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return ContextCompat.getColor(this, android.R.color.white);
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

}
