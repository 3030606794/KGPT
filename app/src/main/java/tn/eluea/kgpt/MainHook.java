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
package tn.eluea.kgpt;

import android.annotation.SuppressLint;
import android.app.Service;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tn.eluea.kgpt.hook.HookManager;
import tn.eluea.kgpt.hook.MethodHook;
import tn.eluea.kgpt.hook.TextSelectionHook;
import tn.eluea.kgpt.hook.ClipboardHook;
import tn.eluea.kgpt.provider.XposedConfigReader;
import tn.eluea.kgpt.ui.IMSController;
import tn.eluea.kgpt.ui.UiInteractor;

public class MainHook implements IXposedHookLoadPackage {
    private static Context applicationContext = null;

    private KGPTBrain brain;

    private HookManager hookManager;

    private Class<?> inputConnectionClass = null;

    private Class<?> inputMethodServiceClass = null;
    
    // Performance optimization: Cache to avoid redundant re-hooking
    private Class<?> lastHookedInputConnectionClass = null;
    private long lastHookTime = 0;
    private static final long MIN_HOOK_INTERVAL_MS = 500; // Minimum interval between hooks

    // Some IMEs run in secondary processes or override onCreate without calling super().
    // Use multiple entry points and guard to avoid duplicate init/hook.
    private static final Set<String> sHookedImeClasses = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Integer> sCreatedImeInstances = Collections.synchronizedSet(new HashSet<>());

    // ===== IME metrics bridge (for floating pad to follow the keyboard) =====
    private static volatile boolean sLastImeVisible = false;
    private static volatile int sLastImeTop = -1;
    private static volatile int sLastImeHeight = -1;
    private static volatile long sLastImeSentAt = 0;
    private static final long IME_METRICS_THROTTLE_MS = 120;

    // Track IME window layout changes so we can update metrics when the keyboard height changes
    // (e.g. candidate bar, emoji panel, different height settings).
    private static final java.util.Map<InputMethodService, android.view.ViewTreeObserver.OnGlobalLayoutListener>
            sImeLayoutListeners = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    // ===== Host app IME metrics (does NOT require the module to be enabled for the keyboard/IME package) =====
    private static final java.util.Map<android.view.View, android.view.ViewTreeObserver.OnGlobalLayoutListener>
            sHostImeLayoutListeners = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile boolean sHostImeHooksInstalled = false;


    private void installImeLayoutListener(InputMethodService ims) {
        if (ims == null) return;
        try {
            if (sImeLayoutListeners.containsKey(ims)) return;

            View decor = null;
            try {
                if (ims.getWindow() != null && ims.getWindow().getWindow() != null) {
                    decor = ims.getWindow().getWindow().getDecorView();
                }
            } catch (Throwable ignored) {}
            if (decor == null) return;

            final java.lang.ref.WeakReference<InputMethodService> ref = new java.lang.ref.WeakReference<>(ims);
            android.view.ViewTreeObserver.OnGlobalLayoutListener l = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    InputMethodService im = ref.get();
                    if (im == null) return;
                    sendImeMetrics(im, true);
                }
            };

            try {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(l);
                sImeLayoutListeners.put(ims, l);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void uninstallImeLayoutListener(InputMethodService ims) {
        if (ims == null) return;
        try {
            android.view.ViewTreeObserver.OnGlobalLayoutListener l = sImeLayoutListeners.remove(ims);
            if (l == null) return;
            View decor = null;
            try {
                if (ims.getWindow() != null && ims.getWindow().getWindow() != null) {
                    decor = ims.getWindow().getWindow().getDecorView();
                }
            } catch (Throwable ignored) {}
            if (decor == null) return;
            try {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(l);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    
    // Install hooks in host apps to detect IME visibility/height using WindowInsets (preferred) or
    // visible display frame (fallback). This avoids requiring the module to be enabled for the IME
    // package itself.
    private void hookHostImeInsetsIfNeeded() {
        if (sHostImeHooksInstalled) return;
        sHostImeHooksInstalled = true;

        try {
            // Activity hooks are in the boot classloader; hookAllMethods is safe.
            XposedBridge.hookAllMethods(android.app.Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        android.app.Activity a = (android.app.Activity) param.thisObject;
                        if (a == null) return;
                        ensureInitialized(a.getApplicationContext());
                        installHostImeLayoutListener(a);
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(android.app.Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        android.app.Activity a = (android.app.Activity) param.thisObject;
                        if (a == null) return;
                        // Best effort: when activity pauses, stop showing the pad.
                        sendImeMetricsFromApp(a.getApplicationContext(), false, -1, -1);
                    } catch (Throwable ignored) {}
                }
            });

            XposedBridge.hookAllMethods(android.app.Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    try {
                        android.app.Activity a = (android.app.Activity) param.thisObject;
                        if (a == null) return;
                        uninstallHostImeLayoutListener(a);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    private void installHostImeLayoutListener(android.app.Activity a) {
        if (a == null) return;
        try {
            if (a.getWindow() == null) return;
            final android.view.View decor = a.getWindow().getDecorView();
            if (decor == null) return;
            if (sHostImeLayoutListeners.containsKey(decor)) return;

            final java.lang.ref.WeakReference<android.app.Activity> ref = new java.lang.ref.WeakReference<>(a);
            android.view.ViewTreeObserver.OnGlobalLayoutListener l = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    android.app.Activity act = ref.get();
                    if (act == null) return;
                    try {
                        Context ctx = act.getApplicationContext();
                        if (ctx == null) return;

                        boolean visible = false;
                        int h = -1;
                        int top = -1;

                        if (android.os.Build.VERSION.SDK_INT >= 30) {
                            try {
                                android.view.WindowInsets wi = decor.getRootWindowInsets();
                                if (wi != null) {
                                    visible = wi.isVisible(android.view.WindowInsets.Type.ime());
                                    android.graphics.Insets ime = wi.getInsets(android.view.WindowInsets.Type.ime());
                                    if (ime != null) h = ime.bottom;
                                }
                            } catch (Throwable ignored) {}
                        }

                        // Fallback for older APIs or OEM ROMs where rootWindowInsets is null.
                        if (h <= 0) {
                            try {
                                android.graphics.Rect r = new android.graphics.Rect();
                                decor.getWindowVisibleDisplayFrame(r);
                                int fullH = decor.getRootView() != null ? decor.getRootView().getHeight() : 0;
                                int visH = r.height();
                                int diff = (fullH > 0 && visH > 0) ? (fullH - visH) : 0;
                                int threshold = dpToPx(ctx, 120);
                                visible = diff > threshold;
                                h = visible ? diff : 0;
                                top = r.bottom;
                            } catch (Throwable ignored) {}
                        }

                        if (visible && h > 0) {
                            // Compute keyboard top in screen coordinates: windowBottom - imeHeight.
                            int[] loc = new int[2];
                            decor.getLocationOnScreen(loc);
                            int windowBottom = loc[1] + decor.getHeight();
                            top = windowBottom - h;
                        }

                        sendImeMetricsFromApp(ctx, visible, top, h);
                    } catch (Throwable ignored) {}
                }
            };

            try {
                decor.getViewTreeObserver().addOnGlobalLayoutListener(l);
                sHostImeLayoutListeners.put(decor, l);
                // Trigger once immediately.
                try { l.onGlobalLayout(); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void uninstallHostImeLayoutListener(android.app.Activity a) {
        if (a == null) return;
        try {
            if (a.getWindow() == null) return;
            android.view.View decor = a.getWindow().getDecorView();
            if (decor == null) return;
            android.view.ViewTreeObserver.OnGlobalLayoutListener l = sHostImeLayoutListeners.remove(decor);
            if (l == null) return;
            try {
                decor.getViewTreeObserver().removeOnGlobalLayoutListener(l);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private int dpToPx(Context ctx, int dp) {
        try {
            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            float d = dm != null ? dm.density : 1f;
            return (int) (dp * d + 0.5f);
        } catch (Throwable ignored) {
            return dp;
        }
    }

private void sendImeMetrics(InputMethodService ims, boolean visible) {
        if (ims == null) return;
        try {
            final Context ctx = ims.getApplicationContext();
            if (ctx == null) return;

            int top = -1;
            int h = -1;
            if (visible) {
                try {
                    View decor = null;
                    try {
                        if (ims.getWindow() != null && ims.getWindow().getWindow() != null) {
                            decor = ims.getWindow().getWindow().getDecorView();
                        }
                    } catch (Throwable ignored) {}

                    if (decor != null) {
                        int[] loc = new int[2];
                        decor.getLocationOnScreen(loc);
                        top = loc[1];
                        h = decor.getHeight();
                    }
                } catch (Throwable ignored) {}

                // Many IME implementations report a bogus Y=0 for their decor location. In that case,
                // we can still derive the keyboard top from the reported height (IME is anchored
                // to the bottom of the screen).
                try {
                    if (h > 0) {
                        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        int screenH = dm != null ? dm.heightPixels : 0;
                        if (screenH > 0) {
                            int derivedTop = screenH - h;
                            if (top <= 0 || top > screenH - 1) {
                                top = derivedTop;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                // Fallback: if not laid out yet, keep last known values.
                if (top <= 0) top = sLastImeTop;
                if (h <= 0) h = sLastImeHeight;
            }

            sendImeMetricsFromApp(ctx, visible, top, h);
        } catch (Throwable ignored) {
        }
    }

    private void sendImeMetricsFromApp(Context ctx, boolean visible, int top, int h) {
        if (ctx == null) return;
        try {
            if (visible) {
                // Fallback to last known values if caller couldn't compute metrics yet.
                if (top <= 0) top = sLastImeTop;
                if (h <= 0) h = sLastImeHeight;
            }

            long now = System.currentTimeMillis();
            if (now - sLastImeSentAt < IME_METRICS_THROTTLE_MS) {
                // Still send if visibility changed.
                if (visible == sLastImeVisible) return;
            }

            boolean changed = (visible != sLastImeVisible)
                    || (visible && (Math.abs(top - sLastImeTop) > 1 || Math.abs(h - sLastImeHeight) > 1));
            if (!changed) return;

            sLastImeVisible = visible;
            if (visible) {
                sLastImeTop = top;
                sLastImeHeight = h;
            }
            sLastImeSentAt = now;

            try {
                android.content.Intent i = new android.content.Intent(UiInteractor.ACTION_FB_IME_METRICS);
                i.setPackage(tn.eluea.kgpt.BuildConfig.APPLICATION_ID);
                i.putExtra(UiInteractor.EXTRA_IME_VISIBLE, visible);
                if (visible) {
                    i.putExtra(UiInteractor.EXTRA_IME_TOP, sLastImeTop);
                    i.putExtra(UiInteractor.EXTRA_IME_HEIGHT, sLastImeHeight);
                }
                ctx.sendBroadcast(i);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }


    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
if (lpparam.packageName.equals("tn.eluea.kgpt")) {
            MainHook.log("Hooking own module for status check");
            // Hook the module status check method
            XposedHelpers.findAndHookMethod(
                    "tn.eluea.kgpt.ui.main.fragments.HomeFragment",
                    lpparam.classLoader,
                    "isModuleActiveInternal",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            param.setResult(true);
                        }
                    });
            return;
        }

        MainHook.log("Loading KGPT for package " + lpparam.packageName);

        if ("android".equals(lpparam.packageName)) {
            // System framework: only install global clipboard capture
            ClipboardHook.hook(lpparam);
            return;
        }

        // Log XSharedPreferences status early
        MainHook.log("XSharedPreferences available: " + XposedConfigReader.isAvailable());
        MainHook.log(XposedConfigReader.getDebugInfo());

        // Hook text selection for AI actions (works in any app)
        TextSelectionHook.hook(lpparam);

        // Keep the floating pad always aligned above the keyboard in any host app.
        hookHostImeInsetsIfNeeded();

        hookKeyboard(lpparam);
    }

    private void ensureInitialized(Context applicationContext) {
    if (MainHook.applicationContext == null) {
        MainHook.applicationContext = applicationContext;

        SPManager.init(applicationContext);
        UiInteractor.init(applicationContext);

        brain = new KGPTBrain(applicationContext);
    }
}

private void onImeCreated(InputMethodService ims, String source) {
    if (ims == null) return;
    try {
        int id = System.identityHashCode(ims);
        if (sCreatedImeInstances.contains(id)) {
            return;
        }
        sCreatedImeInstances.add(id);
    } catch (Throwable ignored) {}

    MainHook.log("InputMethodService created via " + source);

    ensureInitialized(ims.getApplicationContext());

    try { IMSController.getInstance().resetShadow(); } catch (Throwable ignored) {}

    UiInteractor.getInstance().onInputMethodCreate(ims);

    inputMethodServiceClass = ims.getClass();
    MainHook.log("InputMethodService : " + inputMethodServiceClass.getName());

    // Hook methods only once per IME class to avoid duplicate hooks
    if (sHookedImeClasses.add(inputMethodServiceClass.getName())) {
        hookMethodService();
    }
}

private void hookKeyboard(XC_LoadPackage.LoadPackageParam lpparam) {
        hookManager = new HookManager();

        // Robust entry-point: Service.attach() is always called for services, even if onCreate is overridden.
        XposedBridge.hookAllMethods(Service.class, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (!(param.thisObject instanceof InputMethodService)) return;
                InputMethodService ims = (InputMethodService) param.thisObject;
                onImeCreated(ims, "Service.attach");
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                InputMethodService ims = (InputMethodService) param.thisObject;
                onImeCreated(ims, "InputMethodService.onCreate");
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onDestroy", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                MainHook.log("InputMethodService onDestroy");
                InputMethodService ims = (InputMethodService) param.thisObject;
                UiInteractor.getInstance().onInputMethodDestroy(ims);
                
                // Clean up brain resources
                if (brain != null) {
                    brain.destroy();
                    brain = null;
                }
                
                // Reset hook cache
                lastHookedInputConnectionClass = null;
                lastHookTime = 0;
            }
        });

        XposedHelpers.findAndHookMethod(InputMethodService.class, "onFinishInput", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                MainHook.log("InputMethodService onFinishInput");
            }
        });

        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Application app = (Application) param.args[0];
                        ensureInitialized(app.getApplicationContext());
                    }
                });
    }

    private void hookMethodService() {
        hookManager.hook(inputMethodServiceClass, "onUpdateSelection",
                new Class<?>[] { int.class, int.class, int.class, int.class, int.class, int.class },
                MethodHook.after(param -> {
                    InputMethodService ims = (InputMethodService) param.thisObject;
                    String packageName = ims.getCurrentInputEditorInfo().packageName;
                    if (BuildConfig.APPLICATION_ID.equals(packageName)) {
                        return;
                    }

                    int oldSelStart = (int) param.args[0];
                    int oldSelEnd = (int) param.args[1];
                    int newSelStart = (int) param.args[2];
                    int newSelEnd = (int) param.args[3];

                    // Notify IMSController for text parsing
                    IMSController.getInstance().onUpdateSelection(
                            oldSelStart,
                            oldSelEnd,
                            newSelStart,
                            newSelEnd,
                            (int) param.args[4],
                            (int) param.args[5]);

                    // Notify SelectionHandler for text actions
                    if (brain != null && brain.getSelectionHandler() != null) {
                        brain.getSelectionHandler().onSelectionChanged(
                                ims, oldSelStart, oldSelEnd, newSelStart, newSelEnd);
                    }
                }));

        hookManager.hook(inputMethodServiceClass, "onStartInput",
                new Class<?>[] { EditorInfo.class, boolean.class }, MethodHook.after(param -> {
                    InputMethodService ims = (InputMethodService) param.thisObject;
                        
                    // Performance optimization: Skip if InputConnection hasn't changed
                    if (ims.getCurrentInputConnection() == null) {
                        return;
                    }
                    
                    Class<?> newInputConnectionClass = ims.getCurrentInputConnection().getClass();
                    long currentTime = System.currentTimeMillis();
                    
                    // Skip re-hooking if same class and within minimum interval
                    if (newInputConnectionClass.equals(lastHookedInputConnectionClass) 
                            && (currentTime - lastHookTime) < MIN_HOOK_INTERVAL_MS) {
                        return;
                    }
                    
                    // Only unhook and rehook if the class actually changed
                    if (!newInputConnectionClass.equals(lastHookedInputConnectionClass)) {
                        hookManager.unhook(m -> m.getClass().equals(inputConnectionClass));
                        
                        MainHook.log("InputMethodService onStartInput");
                        inputMethodServiceClass = ims.getClass();
                        inputConnectionClass = newInputConnectionClass;
                        lastHookedInputConnectionClass = newInputConnectionClass;
                        MainHook.log("InputMethodService InputConnection : " + inputConnectionClass.getName());
                        
                        hookInputConnection();
                    }
                    
                    lastHookTime = currentTime;
                }));

        // ===== IME window visibility/metrics for floating pad =====
        try {
            hookManager.hook(inputMethodServiceClass, "onWindowShown",
                    new Class<?>[] {}, MethodHook.after(param -> {
                        final InputMethodService ims = (InputMethodService) param.thisObject;
                        installImeLayoutListener(ims);
                        try {
                            final Handler h = new Handler(Looper.getMainLooper());
                            Runnable r = () -> sendImeMetrics(ims, true);
                            h.post(r);
                            h.postDelayed(r, 140);
                            h.postDelayed(r, 420);
                        } catch (Throwable ignored) {
                            sendImeMetrics(ims, true);
                        }
                    }));
        } catch (Throwable ignored) {
        }

        try {
            hookManager.hook(inputMethodServiceClass, "onWindowHidden",
                    new Class<?>[] {}, MethodHook.after(param -> {
                        InputMethodService ims = (InputMethodService) param.thisObject;
                        uninstallImeLayoutListener(ims);
                        sendImeMetrics(ims, false);
                    }));
        } catch (Throwable ignored) {
        }

        try {
            hookManager.hook(inputMethodServiceClass, "onStartInputView",
                    new Class<?>[] { EditorInfo.class, boolean.class }, MethodHook.after(param -> {
                        final InputMethodService ims = (InputMethodService) param.thisObject;
                        installImeLayoutListener(ims);
                        try {
                            final Handler h = new Handler(Looper.getMainLooper());
                            Runnable r = () -> sendImeMetrics(ims, true);
                            h.post(r);
                            h.postDelayed(r, 140);
                            h.postDelayed(r, 420);
                        } catch (Throwable ignored2) {
                            sendImeMetrics(ims, true);
                        }
                    }));
        } catch (Throwable ignored) {
        }

        try {
            hookManager.hook(inputMethodServiceClass, "onFinishInputView",
                    new Class<?>[] { boolean.class }, MethodHook.after(param -> {
                        InputMethodService ims = (InputMethodService) param.thisObject;
                        uninstallImeLayoutListener(ims);
                        sendImeMetrics(ims, false);
                    }));
        } catch (Throwable ignored) {
        }
        MainHook.log("Done hooking InputMethodService : " + inputMethodServiceClass.getName());
    }

    @SuppressLint("ObsoleteSdkInt")
    private void hookInputConnection() {
        XC_MethodHook gateOnly = new MethodHook(param -> {
            if (IMSController.getInstance().isInputLocked()) {
                param.setResult(false);
            }
        }, null);

        // Trigger parsing directly from InputConnection events.
        // Some keyboards override InputMethodService.onUpdateSelection() without calling super(),
        // so relying only on onUpdateSelection can break keyword triggers (AI 触发器).
        XC_MethodHook gateAndUpdate = new MethodHook(param -> {
            if (IMSController.getInstance().isInputLocked()) {
                param.setResult(false);
            }
        }, param -> {
            if (IMSController.getInstance().isInputLocked()) {
                return;
            }
try {
    String m = param.method.getName();
    if ("commitText".equals(m) || "setComposingText".equals(m)) {
        CharSequence cs = (CharSequence) param.args[0];
        boolean composing = "setComposingText".equals(m);
        IMSController.getInstance().onInputEventText(cs, composing);
    } else if ("finishComposingText".equals(m)) {
        IMSController.getInstance().onInputEventFinishComposing();
    } else if ("deleteSurroundingText".equals(m) || "deleteSurroundingTextInCodePoints".equals(m)) {
        int before = (int) param.args[0];
        int after = (int) param.args[1];
        IMSController.getInstance().onInputEventDelete(before, after);
    } else if (Build.VERSION.SDK_INT >= 34 && "replaceText".equals(m)) {
        int start = (int) param.args[0];
        int end = (int) param.args[1];
        CharSequence cs = (CharSequence) param.args[2];
        IMSController.getInstance().onInputEventReplace(start, end, cs);
    }

    IMSController.getInstance().requestTextUpdateFromInputEvent(
            (android.view.inputmethod.InputConnection) param.thisObject);
} catch (Throwable ignored) {
}
        });

        hookManager.hook(inputConnectionClass, "commitText",
                new Class<?>[] { CharSequence.class, int.class }, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "commitCorrection",
                new Class<?>[] { android.view.inputmethod.CorrectionInfo.class }, gateOnly);
        hookManager.hook(inputConnectionClass, "commitCompletion",
                new Class<?>[] { android.view.inputmethod.CompletionInfo.class }, gateOnly);
        hookManager.hook(inputConnectionClass, "setComposingText",
                new Class<?>[] { CharSequence.class, int.class }, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "finishComposingText",
                new Class<?>[] {}, gateAndUpdate);
        hookManager.hook(inputConnectionClass, "deleteSurroundingText",
                new Class<?>[] { int.class, int.class }, gateAndUpdate);

if (Build.VERSION.SDK_INT >= 24) {
            hookManager.hook(inputConnectionClass, "deleteSurroundingTextInCodePoints",
                    new Class<?>[] { int.class, int.class }, gateAndUpdate);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            hookManager.hook(inputConnectionClass, "commitText",
                    new Class<?>[] { CharSequence.class, int.class,
                            android.view.inputmethod.TextAttribute.class },
                    gateAndUpdate);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            hookManager.hook(inputConnectionClass, "replaceText",
                    new Class<?>[] { int.class, int.class, CharSequence.class, int.class,
                            android.view.inputmethod.TextAttribute.class },
                    gateAndUpdate);
        }

        MainHook.log("Done hooking InputConnection : " + inputConnectionClass.getName());
    }

    // Flag to check if we're in Xposed context
    private static final boolean IS_XPOSED_CONTEXT;
    static {
        boolean xposedAvailable = false;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            xposedAvailable = true;
        } catch (ClassNotFoundException e) {
            xposedAvailable = false;
        }
        IS_XPOSED_CONTEXT = xposedAvailable;
    }

    public static void logST() {
        log(Log.getStackTraceString(new Throwable()));
    }

    public static void log(String message) {
        tn.eluea.kgpt.util.Logger.log(message);
    }

    public static void log(Throwable t) {
        // In app context, use Android Log
        if (!IS_XPOSED_CONTEXT) {
            Log.e("KGPT", "Error", t);
            return;
        }

        // In Xposed context, use XposedBridge.log
        XposedBridge.log(t);

        UiInteractor.getInstance().post(
                () -> UiInteractor.getInstance().toastLong(t.getClass().getSimpleName() + " : " + t.getMessage()));
    }
}
