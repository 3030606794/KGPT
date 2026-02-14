/*
 * KGPT - AI in your keyboard
 *
 * AIClipboardStore: a lightweight clipboard history that is shared between
 * the KGPT app UI and the Xposed module side (works across processes).
 *
 * Storage:
 * - Uses KGPT ConfigProvider (ContentProvider) so host apps (Xposed context)
 *   can write entries and the KGPT UI can read them.
 */
package tn.eluea.kgpt.clipboard;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.provider.ConfigProvider;

public final class AIClipboardStore {

    // Always prefer KGPT's own package context when running from a hooked/other process.
    // This makes reads/writes to the ContentProvider much more reliable across OEM ROMs
    // and avoids edge-cases where the host app context can't resolve the provider.
    private static final String KGPT_PKG = "tn.eluea.kgpt";

    private static final String KEY_HISTORY = "ai_clipboard_history_v1";
    // User-requested: keep up to 10k items.
    private static final int MAX_ITEMS = 10_000;
    private static final int MAX_TEXT_LEN = 20_000;

    private AIClipboardStore() {}

    private static Context preferKgptContext(Context ctx) {
        if (ctx == null) return null;
        try {
            // CONTEXT_IGNORE_SECURITY is needed when running in other apps via LSPosed.
            Context pkg = ctx.createPackageContext(KGPT_PKG, Context.CONTEXT_IGNORE_SECURITY);
            return pkg != null ? pkg : ctx;
        } catch (Throwable ignored) {
            return ctx;
        }
    }

    public static final class Entry {
        public final int storeIndex; // index inside stored JSONArray
        public final long timeMs;
        public final String text;
        public final boolean favorite;
        public final String group;

        public Entry(int storeIndex, long timeMs, String text, boolean favorite, String group) {
            this.storeIndex = storeIndex;
            this.timeMs = timeMs;
            this.text = text;
            this.favorite = favorite;
            this.group = group;
        }
    }

    public static int getCount(Context ctx) {
        ctx = preferKgptContext(ctx);
        JSONArray arr = readArray(ctx);
        return arr != null ? arr.length() : 0;
    }

    /** Returns the maximum number of entries retained in the AI clipboard history. */
    public static int getMaxItems() {
        return MAX_ITEMS;
    }

    /**
     * Returns entries newest-first.
     */
    public static List<Entry> getEntries(Context ctx) {
        return getEntries(ctx, false);
    }

    /**
     * Returns entries newest-first.
     * @param favoritesOnly if true, only return favorited items.
     */
    public static List<Entry> getEntries(Context ctx, boolean favoritesOnly) {
        ctx = preferKgptContext(ctx);
        JSONArray arr = readArray(ctx);
        if (arr == null || arr.length() == 0) return Collections.emptyList();

        ArrayList<Entry> out = new ArrayList<>();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            long t = o.optLong("t", 0L);
            String text = o.optString("text", "");
            if (text == null) text = "";
            text = text.trim();
            if (text.isEmpty()) continue;
            boolean fav = o.optInt("fav", 0) == 1;
            String group = o.optString("g", "");
            if (group == null) group = "";
            group = group.trim();
            if (favoritesOnly && !fav) continue;
            out.add(new Entry(i, t, text, fav, group));
        }
        return out;
    }

    /** Returns only favorited entries newest-first. */
    public static List<Entry> getFavoriteEntries(Context ctx) {
        return getEntries(ctx, true);
    }

    public static void append(Context ctx, String text) {
        ctx = preferKgptContext(ctx);
        if (ctx == null || text == null) return;
        String v = text.trim();
        if (v.isEmpty()) return;
        if (v.length() > MAX_TEXT_LEN) {
            v = v.substring(0, MAX_TEXT_LEN);
        }

        JSONArray arr = readArray(ctx);
        if (arr == null) arr = new JSONArray();

        // Avoid duplicate consecutive entries
        if (arr.length() > 0) {
            JSONObject last = arr.optJSONObject(arr.length() - 1);
            if (last != null) {
                String lastText = last.optString("text", "");
                if (lastText != null && lastText.trim().equals(v)) {
                    return;
                }
            }
        }

        JSONObject o = new JSONObject();
        try {
            o.put("t", System.currentTimeMillis());
            o.put("text", v);
            o.put("fav", 0);
            o.put("g", "");
        } catch (Exception ignored) {}

        arr.put(o);

        // Trim to MAX_ITEMS
        if (arr.length() > MAX_ITEMS) {
            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, arr.length() - MAX_ITEMS);
            for (int i = start; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item != null) trimmed.put(item);
            }
            arr = trimmed;
        }

        writeArray(ctx, arr);
    }

    public static void toggleFavorite(Context ctx, int storeIndex) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;
        JSONArray arr = readArray(ctx);
        if (arr == null) return;
        if (storeIndex < 0 || storeIndex >= arr.length()) return;

        JSONObject o = arr.optJSONObject(storeIndex);
        if (o == null) return;
        boolean fav = o.optInt("fav", 0) == 1;
        try {
            o.put("fav", fav ? 0 : 1);
        } catch (Exception ignored) {}

        // Re-set object in array (some JSON implementations require this)
        try { arr.put(storeIndex, o); } catch (Exception ignored) {}
        writeArray(ctx, arr);
    }

    public static void setFavorite(Context ctx, int storeIndex, boolean favorite) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;
        JSONArray arr = readArray(ctx);
        if (arr == null) return;
        if (storeIndex < 0 || storeIndex >= arr.length()) return;

        JSONObject o = arr.optJSONObject(storeIndex);
        if (o == null) return;
        try {
            o.put("fav", favorite ? 1 : 0);
            arr.put(storeIndex, o);
        } catch (Exception ignored) {}
        writeArray(ctx, arr);
    }


    public static void setGroup(Context ctx, int storeIndex, String groupName) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;
        JSONArray arr = readArray(ctx);
        if (arr == null) return;
        if (storeIndex < 0 || storeIndex >= arr.length()) return;

        JSONObject o = arr.optJSONObject(storeIndex);
        if (o == null) return;

        String g = groupName != null ? groupName.trim() : "";
        if (g.length() > 80) g = g.substring(0, 80);

        try {
            o.put("g", g);
            arr.put(storeIndex, o);
        } catch (Exception ignored) {}

        writeArray(ctx, arr);
    }

    /** Returns the group name for a stored entry (trimmed). Empty string means "All". */
    public static String getGroup(Context ctx, int storeIndex) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return "";
        JSONArray arr = readArray(ctx);
        if (arr == null) return "";
        if (storeIndex < 0 || storeIndex >= arr.length()) return "";
        JSONObject o = arr.optJSONObject(storeIndex);
        if (o == null) return "";
        String g = o.optString("g", "");
        if (g == null) g = "";
        return g.trim();
    }

    
    /**
     * Rename a group for all stored entries.
     * This keeps existing clipboard items aligned with the renamed group.
     */
    public static void renameGroup(Context ctx, String fromGroup, String toGroup) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;

        String from = fromGroup != null ? fromGroup.trim() : "";
        String to = toGroup != null ? toGroup.trim() : "";
        if (from.isEmpty() || to.isEmpty()) return;
        if (from.equalsIgnoreCase(to)) return;
        if (to.length() > 80) to = to.substring(0, 80);

        JSONArray arr = readArray(ctx);
        if (arr == null || arr.length() == 0) return;

        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String g = o.optString("g", "");
            if (g == null) g = "";
            g = g.trim();
            if (!from.equalsIgnoreCase(g)) continue;
            try {
                o.put("g", to);
                arr.put(i, o);
                changed = true;
            } catch (Exception ignored) {}
        }

        if (changed) writeArray(ctx, arr);
    }

    /**
     * Clear a group for all stored entries (move them back to "All").
     * This does NOT delete entries; it only sets their group field to empty.
     */
    public static void clearGroup(Context ctx, String groupName) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;

        String target = groupName != null ? groupName.trim() : "";
        if (target.isEmpty()) return;

        JSONArray arr = readArray(ctx);
        if (arr == null || arr.length() == 0) return;

        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String g = o.optString("g", "");
            if (g == null) g = "";
            g = g.trim();
            if (!target.equalsIgnoreCase(g)) continue;
            try {
                o.put("g", "");
                arr.put(i, o);
                changed = true;
            } catch (Exception ignored) {}
        }

        if (changed) writeArray(ctx, arr);
    }

public static void deleteAt(Context ctx, int storeIndex) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;
        JSONArray arr = readArray(ctx);
        if (arr == null) return;
        if (storeIndex < 0 || storeIndex >= arr.length()) return;

        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i == storeIndex) continue;
            Object item = arr.opt(i);
            if (item != null) out.put(item);
        }
        writeArray(ctx, out);
    }

    public static void clear(Context ctx) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;
        writeString(ctx, KEY_HISTORY, "[]");
    }

    /**
     * Clear ONLY non-favorited items. Favorited items are preserved.
     * User-requested: tapping "Clear" should not remove favorite entries.
     */
    public static void clearNonFavorites(Context ctx) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;

        JSONArray arr = readArray(ctx);
        if (arr == null || arr.length() == 0) {
            writeString(ctx, KEY_HISTORY, "[]");
            return;
        }

        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean fav = o.optInt("fav", 0) == 1;
            if (!fav) continue;
            out.put(o);
        }
        writeArray(ctx, out);
    }

    /**
     * Clear ONLY non-favorited items that belong to the given group.
     * - groupName == "" (or null) means: clear ONLY ungrouped (All) non-favorites.
     * - groupName != "" means: clear ONLY that group's non-favorites.
     * Favorited items are always preserved.
     *
     * User-requested behavior:
     * - In "All" view: clearing should NOT remove items that were put into a group.
     */
    public static void clearNonFavoritesInGroup(Context ctx, String groupName) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;

        String target = groupName != null ? groupName.trim() : "";

        JSONArray arr = readArray(ctx);
        if (arr == null || arr.length() == 0) {
            writeString(ctx, KEY_HISTORY, "[]");
            return;
        }

        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            boolean fav = o.optInt("fav", 0) == 1;
            if (fav) {
                out.put(o);
                continue;
            }
            String g = o.optString("g", "");
            if (g == null) g = "";
            g = g.trim();

            // Delete only if this non-favorite is in the target group.
            if (target.equalsIgnoreCase(g)) {
                continue;
            }
            out.put(o);
        }
        writeArray(ctx, out);
    }


    /**
     * Update an existing entry text (and refresh timestamp).
     * If newText is empty after trimming, the entry will be deleted.
     */
    public static void updateText(Context ctx, int storeIndex, String newText) {
        ctx = preferKgptContext(ctx);
        if (ctx == null) return;

        if (newText == null) newText = "";
        String v = newText.trim();
        if (v.isEmpty()) {
            deleteAt(ctx, storeIndex);
            return;
        }
        if (v.length() > MAX_TEXT_LEN) {
            v = v.substring(0, MAX_TEXT_LEN);
        }

        JSONArray arr = readArray(ctx);
        if (arr == null) return;
        if (storeIndex < 0 || storeIndex >= arr.length()) return;

        JSONObject o = arr.optJSONObject(storeIndex);
        if (o == null) return;
        try {
            o.put("text", v);
            o.put("t", System.currentTimeMillis());
            arr.put(storeIndex, o);
        } catch (Exception ignored) {
        }
        writeArray(ctx, arr);
    }

    // ------------------
    // Provider helpers
    // ------------------

    private static JSONArray readArray(Context ctx) {
        try {
            String raw = readString(ctx, KEY_HISTORY, "[]");
            if (raw == null || raw.trim().isEmpty()) raw = "[]";
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeArray(Context ctx, JSONArray arr) {
        if (arr == null) arr = new JSONArray();
        writeString(ctx, KEY_HISTORY, arr.toString());
    }

    private static String readString(Context ctx, String key, String def) {
        try {
            ContentResolver r = ctx.getContentResolver();
            Uri uri = Uri.withAppendedPath(ConfigProvider.CONTENT_URI, key);
            Cursor c = r.query(uri, null, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        int idx = c.getColumnIndex(ConfigProvider.COLUMN_VALUE);
                        if (idx >= 0) {
                            String v = c.getString(idx);
                            return v != null ? v : def;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static void writeString(Context ctx, String key, String value) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(ConfigProvider.COLUMN_KEY, key);
            cv.put(ConfigProvider.COLUMN_VALUE, value);
            cv.put(ConfigProvider.COLUMN_TYPE, ConfigProvider.TYPE_STRING);
            ctx.getContentResolver().insert(ConfigProvider.CONTENT_URI, cv);
        } catch (Exception ignored) {}
    }
}
