/*
 * KGPT - AI in your keyboard
 *
 * ClipboardHook:
 * Capture system clipboard changes (copy/cut) and append them to KGPT's
 * "AI剪贴板" history via AIClipboardStore (ContentProvider-backed).
 *
 * Works in two modes:
 * 1) App process: hooks android.content.ClipboardManager#setPrimaryClip
 *    (requires the app to be in LSPosed scope).
 * 2) System framework (recommended): hooks com.android.server.clipboard.ClipboardService
 *    (requires "System Framework/Android" in LSPosed scope) to capture globally.
 */
package tn.eluea.kgpt.hook;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import tn.eluea.kgpt.MainHook;
import tn.eluea.kgpt.clipboard.AIClipboardStore;

public class ClipboardHook {

    private static final String TAG = "KGPT_ClipboardHook";

    // Per-process install guard
    private static volatile boolean sInstalled = false;

    // Simple de-dup guard
    private static volatile String sLastText = null;
    private static volatile long sLastTime = 0L;

    private ClipboardHook() {}

    public static void hook(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sInstalled) return;
        sInstalled = true;

        // 1) App process hook (when KGPT is scoped to the app)
        installClipboardManagerHook(lpparam);

        // 2) System framework global hook (when scoped to "android"/System Framework)
        if ("android".equals(lpparam.packageName)) {
            installSystemServerHook(lpparam);
        }
    }

    private static void installClipboardManagerHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> cmClazz = XposedHelpers.findClass("android.content.ClipboardManager", lpparam.classLoader);

            XposedBridge.hookAllMethods(cmClazz, "setPrimaryClip", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) return;
                        Object arg0 = param.args[0];
                        if (!(arg0 instanceof ClipData)) return;

                        ClipData clip = (ClipData) arg0;

                        Context ctx = tryGetContextFromObject(param.thisObject);
                        if (ctx == null) ctx = getActivityThreadContext();

                        String text = extractText(ctx, clip);
                        saveText(ctx, text);
                    } catch (Throwable t) {
                        // keep silent; avoid crashing host
                    }
                }
            });

            
            // Also register a PrimaryClipChangedListener (more reliable than hooking setPrimaryClip on some IMEs/OEM ROMs).
            try {
                final Context ctx2 = getActivityThreadContext();
                if (ctx2 != null) {
                    ClipboardManager cm2 = (ClipboardManager) ctx2.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm2 != null) {
                        cm2.addPrimaryClipChangedListener(new ClipboardManager.OnPrimaryClipChangedListener() {
                            @Override
                            public void onPrimaryClipChanged() {
                                try {
                                    ClipData clip = cm2.getPrimaryClip();
                                    if (clip == null) return;
                                    String text = extractText(ctx2, clip);
                                    saveText(ctx2, text);
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                        MainHook.log(TAG + ": PrimaryClipChangedListener registered for " + lpparam.packageName);
                    }
                }
            } catch (Throwable ignored) {
            }

            MainHook.log(TAG + ": ClipboardManager hook installed for " + lpparam.packageName);
        } catch (Throwable t) {
            // Some processes may not have ClipboardManager (rare) - ignore
        }
    }

    private static void installSystemServerHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Android 15 / OEM ROMs sometimes dispatch clipboard binder calls through an inner Stub implementation
            // and the outer ClipboardService methods may never be called. We try multiple strategies:
            // 1) Hook known server classes (ClipboardService / inner impl).
            // 2) As a best-effort fallback, hook IClipboard.Stub#onTransact is too heavy; instead we hook the
            //    local binder implementation if we can get it (queryLocalInterface) from ServiceManager.
            tryInstallClipboardServiceHooks(lpparam);

            tryInstallLocalBinderHooks(cl);

        } catch (Throwable t) {
            MainHook.log(TAG + ": Failed to install system clipboard hook: " + t);
        }
    }

    private static void tryInstallClipboardServiceHooks(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            ClassLoader cl = lpparam.classLoader;

            // Prefer the inner binder impl class first.
            Class<?> target = null;
            String[] candidates = new String[] {
                    "com.android.server.clipboard.ClipboardService$ClipboardImpl",
                    "com.android.server.clipboard.ClipboardService$ClipboardServiceImpl",
                    "com.android.server.clipboard.ClipboardService$Clipboard",
                    "com.android.server.clipboard.ClipboardService$ClipboardImplExt",
                    "com.android.server.clipboard.ClipboardService$ClipboardManagerImpl",
                    "com.android.server.clipboard.ClipboardService"
            };

            for (String cn : candidates) {
                try {
                    target = XposedHelpers.findClassIfExists(cn, cl);
                    if (target != null) break;
                } catch (Throwable ignored) {
                }
            }

            if (target == null) {
                MainHook.log(TAG + ": ClipboardService classes not found (OEM may have renamed).");
                return;
            }

            final XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ClipData clip = findClipData(param.args);
                        if (clip == null) return;

                        Context ctx = tryGetContextFromObject(param.thisObject);
                        if (ctx == null) ctx = getActivityThreadContext();
                        if (ctx == null) return;

                        String text = extractText(ctx, clip);
                        saveText(ctx, text);
                    } catch (Throwable ignored) {
                    }
                }
            };

            try { XposedBridge.hookAllMethods(target, "setPrimaryClip", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipAsPackage", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipInternal", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipLocked", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipWithFlags", hook); } catch (Throwable ignored) {}

            MainHook.log(TAG + ": System clipboard hook installed on " + target.getName());
        } catch (Throwable t) {
            MainHook.log(TAG + ": Failed to install ClipboardService hooks: " + t);
        }
    }

    private static void tryInstallLocalBinderHooks(ClassLoader cl) {
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", cl);
            Object binder = XposedHelpers.callStaticMethod(sm, "getService", "clipboard");
            if (binder == null) return;

            // Only works if the returned binder is local Binder (in system_server), otherwise it's a BinderProxy.
            Object local = null;
            try {
                local = XposedHelpers.callMethod(binder, "queryLocalInterface", "android.content.IClipboard");
            } catch (Throwable ignored) {
            }
            if (local == null) {
                // Not local on this ROM; keep ClipboardService hooks + app listeners as fallback.
                MainHook.log(TAG + ": clipboard binder is not local (BinderProxy), skip local binder hooks.");
                return;
            }

            Class<?> target = local.getClass();
            final XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        ClipData clip = findClipData(param.args);
                        if (clip == null) return;

                        Context ctx = getActivityThreadContext();
                        if (ctx == null) return;

                        String text = extractText(ctx, clip);
                        saveText(ctx, text);
                    } catch (Throwable ignored) {
                    }
                }
            };

            try { XposedBridge.hookAllMethods(target, "setPrimaryClip", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipAsPackage", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipInternal", hook); } catch (Throwable ignored) {}
            try { XposedBridge.hookAllMethods(target, "setPrimaryClipWithFlags", hook); } catch (Throwable ignored) {}

            MainHook.log(TAG + ": Local clipboard binder hook installed on " + target.getName());
        } catch (Throwable t) {
            MainHook.log(TAG + ": Failed to install local binder clipboard hooks: " + t);
        }
    }

    private static ClipData findClipData(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof ClipData) return (ClipData) a;
        }
        return null;
    }

    private static void saveText(Context ctx, String text) {
        if (text == null) return;
        text = text.trim();
        if (text.isEmpty()) return;

        long now = System.currentTimeMillis();
        String last = sLastText;
        long lastT = sLastTime;

        // Debounce duplicates
        if (last != null && last.equals(text) && (now - lastT) < 800) return;

        sLastText = text;
        sLastTime = now;

        // Prefer a valid system context; then create KGPT package context for stable storage.
        if (ctx == null) ctx = getActivityThreadContext();
        if (ctx == null) return;

        Context targetCtx = ctx;
        try {
            // Write using KGPT's package context so reads in the app UI always match.
            targetCtx = ctx.createPackageContext("tn.eluea.kgpt", Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable ignored) {
        }

        try {
            AIClipboardStore.append(targetCtx, text);
        } catch (Throwable ignored) {
            try {
                AIClipboardStore.append(ctx, text);
            } catch (Throwable ignored2) {
            }
        }
    }

    private static String extractText(Context ctx, ClipData clip) {
        try {
            if (clip == null || clip.getItemCount() == 0) return null;

            ClipData.Item item = clip.getItemAt(0);
            if (item == null) return null;

            CharSequence cs = item.getText();
            if (cs != null) return cs.toString();

            if (ctx != null) {
                CharSequence c2 = item.coerceToText(ctx);
                if (c2 != null) return c2.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Context tryGetContextFromObject(Object obj) {
        if (obj == null) return null;
        try {
            Object c = XposedHelpers.getObjectField(obj, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        try {
            Object outer = XposedHelpers.getObjectField(obj, "this$0");
            Object c = XposedHelpers.getObjectField(outer, "mContext");
            if (c instanceof Context) return (Context) c;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Best-effort Context getter without AndroidAppHelper (keeps build compatible).
     */
    private static Context getActivityThreadContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread != null) {
                try {
                    Object sys = XposedHelpers.callMethod(thread, "getSystemContext");
                    if (sys instanceof Context) return (Context) sys;
                } catch (Throwable ignored) {
                }
                try {
                    Object app = XposedHelpers.callMethod(thread, "getApplication");
                    if (app instanceof Context) return (Context) app;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
