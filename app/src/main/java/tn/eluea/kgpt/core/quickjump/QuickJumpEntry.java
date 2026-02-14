package tn.eluea.kgpt.core.quickjump;

import org.json.JSONObject;

import java.util.UUID;

public class QuickJumpEntry {
    public String id;
    public String name;
    public String trigger;
    public String urlTemplate;
    public boolean enabled;

    public QuickJumpEntry() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
    }

    public static QuickJumpEntry fromJson(JSONObject obj) {
        if (obj == null) return null;
        QuickJumpEntry e = new QuickJumpEntry();
        try {
            String id = obj.optString("id", "");
            if (id != null && !id.trim().isEmpty()) e.id = id;
        } catch (Throwable ignored) {}
        e.name = obj.optString("name", "");
        e.trigger = obj.optString("trigger", "");
        e.urlTemplate = obj.optString("url", "");
        // Backward compat key
        if ((e.urlTemplate == null || e.urlTemplate.trim().isEmpty()) && obj.has("urlTemplate")) {
            e.urlTemplate = obj.optString("urlTemplate", "");
        }
        e.enabled = obj.optBoolean("enabled", true);
        return e;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try { obj.put("id", id != null ? id : ""); } catch (Throwable ignored) {}
        try { obj.put("name", name != null ? name : ""); } catch (Throwable ignored) {}
        try { obj.put("trigger", trigger != null ? trigger : ""); } catch (Throwable ignored) {}
        try { obj.put("url", urlTemplate != null ? urlTemplate : ""); } catch (Throwable ignored) {}
        try { obj.put("enabled", enabled); } catch (Throwable ignored) {}
        return obj;
    }
}
