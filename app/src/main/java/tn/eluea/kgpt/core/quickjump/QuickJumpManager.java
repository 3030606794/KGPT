package tn.eluea.kgpt.core.quickjump;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class QuickJumpManager {
    private static final Pattern LEGACY_LINE = Pattern.compile("^\\s*【([^】]+)】\\s*(.+?)\\s*$");

    private QuickJumpManager() {}

    public static List<QuickJumpEntry> load(String config) {
        ArrayList<QuickJumpEntry> out = new ArrayList<>();
        if (config == null) return out;
        String raw = config.trim();
        if (raw.isEmpty()) return out;

        // JSON format
        try {
            if (raw.startsWith("[")) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;
                    QuickJumpEntry e = QuickJumpEntry.fromJson(obj);
                    if (e != null) out.add(e);
                }
                return out;
            } else if (raw.startsWith("{")) {
                JSONObject obj = new JSONObject(raw);
                JSONArray arr = obj.optJSONArray("items");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.optJSONObject(i);
                        if (it == null) continue;
                        QuickJumpEntry e = QuickJumpEntry.fromJson(it);
                        if (e != null) out.add(e);
                    }
                    return out;
                }
            }
        } catch (Throwable ignored) {
            // fall through to legacy parsing
        }

        // Legacy format: split by "##" or lines
        ArrayList<String> parts = new ArrayList<>();
        if (raw.contains("##")) {
            String[] spl = raw.split("##");
            for (String p : spl) {
                if (p == null) continue;
                String s = p.trim();
                if (!s.isEmpty()) parts.add(s);
            }
        } else {
            String[] spl = raw.split("\\r?\\n");
            for (String p : spl) {
                if (p == null) continue;
                String s = p.trim();
                if (!s.isEmpty()) parts.add(s);
            }
        }

        for (String line : parts) {
            if (line == null) continue;
            String s = line.trim();
            if (s.isEmpty()) continue;

            String name = "";
            String url = s;

            try {
                Matcher m = LEGACY_LINE.matcher(s);
                if (m.find()) {
                    name = m.group(1) != null ? m.group(1).trim() : "";
                    url = m.group(2) != null ? m.group(2).trim() : "";
                }
            } catch (Throwable ignored) {}

            QuickJumpEntry e = new QuickJumpEntry();
            e.name = name;
            e.urlTemplate = url;
            e.trigger = ""; // legacy had no triggers
            e.enabled = true;
            out.add(e);
        }

        return out;
    }

    public static String save(List<QuickJumpEntry> items) {
        JSONArray arr = new JSONArray();
        if (items != null) {
            for (QuickJumpEntry e : items) {
                if (e == null) continue;
                arr.put(e.toJson());
            }
        }
        return arr.toString();
    }

    public static int countEnabled(List<QuickJumpEntry> items) {
        if (items == null || items.isEmpty()) return 0;
        int c = 0;
        for (QuickJumpEntry e : items) {
            if (e != null && e.enabled) c++;
        }
        return c;
    }

    public static String buildUrl(String template, String queryRaw) {
        String tpl = template == null ? "" : template.trim();
        String q = queryRaw == null ? "" : queryRaw;
        String enc;
        try {
            enc = URLEncoder.encode(q, "UTF-8");
        } catch (Throwable t) {
            enc = q;
        }

        if (tpl.isEmpty()) return enc;

        boolean hasPlaceholder = tpl.contains("{q}") || tpl.contains("%s") || tpl.contains("😁");
        String url = tpl;
        if (tpl.contains("{q}")) url = url.replace("{q}", enc);
        if (tpl.contains("%s")) url = url.replace("%s", enc);
        if (tpl.contains("😁")) url = url.replace("😁", enc);

        if (!hasPlaceholder) {
            // If no placeholder, append query
            url = url + enc;
        }

        return url;
    }
}
