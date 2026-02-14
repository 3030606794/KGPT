package tn.eluea.kgpt.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Minimal root helper.
 *
 * Strategy:
 * - try to execute "su -c <cmd>"
 * - return exit code
 *
 * This is used for Android 15 where some cross-window operations from the IME/UI
 * context may be restricted. With root (Magisk), we can reliably trigger intents
 * using "am start".
 */
public final class RootShell {
    private RootShell() {}

    public static int execSu(String cmd) {
        if (cmd == null) return -1;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            // Drain streams to avoid deadlock on some devices
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                while (r.readLine() != null) { /* ignore */ }
            } catch (Throwable ignored) {}
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()));
                while (r.readLine() != null) { /* ignore */ }
            } catch (Throwable ignored) {}
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String shellQuoteSingle(String s) {
        // Wrap in single quotes and escape single quotes inside: ' => '\''
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Start a VIEW intent for the given URL via root.
     */
    public static boolean startViewUrl(String url) {
        if (url == null) return false;
        String u = url.trim();
        if (u.isEmpty()) return false;

        String cmd = "am start --user 0 "
                + "-a android.intent.action.VIEW "
                + "-c android.intent.category.BROWSABLE "
                + "-f 0x10000000 "
                + "-d " + shellQuoteSingle(u);

        int code = execSu(cmd);
        return code == 0;
    }
}
