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

import android.content.Context;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashSet;

import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.instruction.command.Commands;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.listener.ConfigInfoProvider;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.provider.ConfigClient;
import tn.eluea.kgpt.settings.OtherSettingsType;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;


import tn.eluea.kgpt.roles.RoleManager;
/**
 * Unified configuration manager that uses ContentProvider as single source of
 * truth.
 * Works in both KGPT app context and Xposed module (Gboard) context.
 */
public class SPManager implements ConfigInfoProvider {
    protected static final String PREF_MODULE_VERSION = "module_version";
    protected static final String PREF_LANGUAGE_MODEL = "language_model_v2";
    protected static final String PREF_GEN_AI_COMMANDS = "gen_ai_commands";
    protected static final String PREF_PARSE_PATTERNS = "parse_patterns";
    protected static final String PREF_OTHER_SETTING = "other_setting.%s";

    // User-defined quick jump templates (deep-link list)
    protected static final String PREF_QUICK_JUMP_CONFIG = "quick_jump_config";

    private final ConfigClient mClient;
    private List<GenerativeAICommand> generativeAICommands = List.of();
    private static SPManager instance = null;

    // Sticky cache for roles to avoid intermittent provider/Xposed read issues
    private volatile String mLastRolesJson = "";
    private volatile String mLastActiveRoleId = tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
    private volatile long mLastRoleSetAtMs = 0L;

    public static void init(Context context) {
        instance = new SPManager(context);
    }

    public static SPManager getInstance() {
        if (instance == null) {
            throw new RuntimeException("Missing call to SPManager.init(Context)");
        }
        return instance;
    }

    public static boolean isReady() {
        return instance != null;
    }

    private SPManager(Context context) {
        mClient = new ConfigClient(context);
        updateVersion();
        initializeDefaultCommands();
        initializeDefaultPatterns();
        updateGenerativeAICommands();
    }

    private void initializeDefaultCommands() {
        String existing = mClient.getString(PREF_GEN_AI_COMMANDS, null);
        if (existing == null || existing.equals("[]")) {
            setGenerativeAICommands(Commands.getDefaultCommands());
        }
    }

    private void initializeDefaultPatterns() {
        String existing = mClient.getString(PREF_PARSE_PATTERNS, null);
        if (existing == null) {
            // Initialize with default patterns
            setParsePatterns(ParsePattern.getDefaultPatterns());
            return;
        }

        // Migration: ensure any newly added PatternType entries exist in the saved list.
        // This keeps updates compatible without forcing users to reset their patterns.
        try {
            List<ParsePattern> patterns = ParsePattern.decode(existing);
            if (patterns == null) patterns = new ArrayList<>();
            boolean changed = false;

            for (PatternType type : PatternType.values()) {
                boolean found = false;
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() == type) { found = true; break; }
                }
                if (!found) {
                    ParsePattern p = new ParsePattern(type, type.defaultPattern);
                    p.setEnabled(true);
                    patterns.add(p);
                    changed = true;
                }
            }

            if (changed) {
                setParsePatterns(patterns);
            }
        } catch (Throwable t) {
            // If parsing fails for any reason, fall back to defaults (better than crashing).
            setParsePatterns(ParsePattern.getDefaultPatterns());
        }
    }

    private void updateVersion() {
        int version = getVersion();
        if (version != BuildConfig.VERSION_CODE) {
            mClient.putInt(PREF_MODULE_VERSION, BuildConfig.VERSION_CODE);
        }
    }

    public int getVersion() {
        return mClient.getInt(PREF_MODULE_VERSION, -1);
    }

    public boolean hasLanguageModel() {
        return mClient.contains(PREF_LANGUAGE_MODEL);
    }

    @Override
    public LanguageModel getLanguageModel() {
        String languageModelName = mClient.getString(PREF_LANGUAGE_MODEL, null);
        if (languageModelName == null) {
            languageModelName = LanguageModel.Gemini.name();
        }
        return LanguageModel.valueOf(languageModelName);
    }

    public void setLanguageModel(LanguageModel model) {
        mClient.putString(PREF_LANGUAGE_MODEL, model.name());
    }

    public void setLanguageModelField(LanguageModel model, LanguageModelField field, String value) {
        if (model == null || field == null) {
            tn.eluea.kgpt.util.Logger.log("setLanguageModelField: model or field is null");
            return;
        }
        String entryName = String.format("%s." + field, model.name());
        mClient.putString(entryName, value);
    }

    public String getLanguageModelField(LanguageModel model, LanguageModelField field) {
        String entryName = String.format("%s." + field, model.name());
        return mClient.getString(entryName, model.getDefault(field));
    }

    public void setApiKey(LanguageModel model, String apiKey) {
        setLanguageModelField(model, LanguageModelField.ApiKey, apiKey);
    }

    public String getApiKey(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.ApiKey);
    }

    public void setSubModel(LanguageModel model, String subModel) {
        setLanguageModelField(model, LanguageModelField.SubModel, subModel);
    }

    public String getSubModel(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.SubModel);
    }

    public void setBaseUrl(LanguageModel model, String baseUrl) {
        setLanguageModelField(model, LanguageModelField.BaseUrl, baseUrl);
    }

    public String getBaseUrl(LanguageModel model) {
        return getLanguageModelField(model, LanguageModelField.BaseUrl);
    }


// ===== Cached Models (for Model Switch) =====
private static final String PREF_CACHED_MODELS_JSON = "cached_models.%s.json";
private static final String PREF_CACHED_MODELS_BASEURL = "cached_models.%s.base_url";

public void setCachedModels(LanguageModel model, String baseUrl, List<String> models) {
    if (model == null) return;

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String keyUrl  = String.format(PREF_CACHED_MODELS_BASEURL, model.name());

    JSONArray arr = new JSONArray();
    HashSet<String> seen = new HashSet<>();

    if (models != null) {
        for (String s : models) {
            if (s == null) continue;
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (seen.add(v)) arr.put(v);
        }
    }

    mClient.putString(keyJson, arr.toString());
    if (baseUrl != null) mClient.putString(keyUrl, baseUrl.trim());
}

public List<String> getCachedModels(LanguageModel model) {
    if (model == null) return Collections.emptyList();

    String keyJson = String.format(PREF_CACHED_MODELS_JSON, model.name());
    String raw = mClient.getString(keyJson, null);
    if (raw == null || raw.trim().isEmpty()) return Collections.emptyList();

    ArrayList<String> out = new ArrayList<>();
    try {
        JSONArray arr = new JSONArray(raw);
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
    } catch (JSONException ignored) {}

    return out;
}

public String getCachedModelsBaseUrl(LanguageModel model) {
    if (model == null) return "";
    String keyUrl = String.format(PREF_CACHED_MODELS_BASEURL, model.name());
    String v = mClient.getString(keyUrl, "");
    return v == null ? "" : v;
}

    // ===== Roles (Personas) =====
    private static final String PREF_ROLES_JSON = tn.eluea.kgpt.roles.RoleManager.PREF_ROLES_JSON;
    private static final String PREF_ACTIVE_ROLE_ID = tn.eluea.kgpt.roles.RoleManager.PREF_ACTIVE_ROLE_ID;
    private static final String PREF_LAST_CUSTOM_ROLE_ID = "last_custom_role_id_v1";
    private static final String PREF_FORCE_DEFAULT_ROLE = "force_default_role_v1";

    public void setRolesJson(String rolesJson) {
        String v = rolesJson != null ? rolesJson : "";
        mLastRolesJson = v;
        mClient.putString(PREF_ROLES_JSON, v);
    }

    public String getRolesJson() {
        String v = mClient.getString(PREF_ROLES_JSON, "");
        if (v == null) v = "";
        // If provider/Xposed returns empty unexpectedly, fall back to last known value
        if (v.isEmpty() && mLastRolesJson != null && !mLastRolesJson.isEmpty()) {
            return mLastRolesJson;
        }
        mLastRolesJson = v;
        return v;
    }

    public void setActiveRoleId(String roleId) {
        String v = roleId != null ? roleId : tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID;
        v = v.trim().isEmpty() ? tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID : v.trim();

        mLastActiveRoleId = v;
        mLastRoleSetAtMs = System.currentTimeMillis();

        // Persist active role
        mClient.putString(PREF_ACTIVE_ROLE_ID, v);

        // Whether the user explicitly forced the DEFAULT role.
        // When true, we must NOT auto-fallback to last custom role.
        mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v));

        // Permanent stickiness: remember last custom role until the user explicitly selects default.
        if (tn.eluea.kgpt.roles.RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, "");
        } else {
            mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v);
        }
    }

    
    public String getActiveRoleId() {
        // Active role id may occasionally read as DEFAULT due to provider race or cache issues,
        // which makes the assistant "jump back" to the default role after a few messages.
        // To keep the selected role stable, we keep a robust fallback chain:
        // 1) value stored in PREF_ACTIVE_ROLE_ID
        // 2) last custom role id (PREF_LAST_CUSTOM_ROLE_ID)
        // 3) in-memory last known role id
        String v = null;
        boolean forceDefault = false;
        try {
            forceDefault = mClient.getBoolean(PREF_FORCE_DEFAULT_ROLE, false);
        } catch (Throwable ignored) {}
        try {
            v = mClient.getString(PREF_ACTIVE_ROLE_ID, RoleManager.DEFAULT_ROLE_ID);
        } catch (Throwable ignored) {
        }
        if (v != null) v = v.trim();
        if (v == null || v.isEmpty()) {
            // read failure or empty -> prefer last custom
            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                // self-heal the active role value so next reads are consistent
                try { mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
                return lastCustom;
            }
            if (mLastActiveRoleId != null) {
                String mem = mLastActiveRoleId.trim();
                if (!mem.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(mem)) {
                    return mem;
                }
            }
            mLastActiveRoleId = RoleManager.DEFAULT_ROLE_ID;
            return RoleManager.DEFAULT_ROLE_ID;
        }

        // If provider returns default but user previously selected a custom role, keep the custom role
        // UNLESS the user explicitly forced the default role.
        if (RoleManager.DEFAULT_ROLE_ID.equals(v)) {
            if (forceDefault) {
                mLastActiveRoleId = v;
                return v;
            }

            String lastCustom = null;
            try {
                lastCustom = mClient.getString(PREF_LAST_CUSTOM_ROLE_ID, "");
            } catch (Throwable ignored) {
            }
            if (lastCustom != null) lastCustom = lastCustom.trim();
            if (lastCustom != null && !lastCustom.isEmpty() && !RoleManager.DEFAULT_ROLE_ID.equals(lastCustom)) {
                mLastActiveRoleId = lastCustom;
                try { 
                    mClient.putString(PREF_ACTIVE_ROLE_ID, lastCustom);
                    mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false);
                } catch (Throwable ignored) {}
                return lastCustom;
            }

            // No last custom role -> trust DEFAULT
            mLastActiveRoleId = v;
            return v;
        }
        // Normal non-default role
        mLastActiveRoleId = v;
        // keep last custom role updated (best-effort)
        try { mClient.putString(PREF_LAST_CUSTOM_ROLE_ID, v); mClient.putBoolean(PREF_FORCE_DEFAULT_ROLE, false); } catch (Throwable ignored) {}
        return v;
    }




    public void setGenerativeAICommandsRaw(String commands) {
        mClient.putString(PREF_GEN_AI_COMMANDS, commands);
        updateGenerativeAICommands();
    }

    public String getGenerativeAICommandsRaw() {
        return mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
    }

    public void setGenerativeAICommands(List<GenerativeAICommand> commands) {
        setGenerativeAICommandsRaw(Commands.encodeCommands(commands));
    }

    public List<GenerativeAICommand> getGenerativeAICommands() {
        // Always get fresh data
        updateGenerativeAICommands();
        return generativeAICommands;
    }

    public void setParsePatterns(List<ParsePattern> parsePatterns) {
        setParsePatternsRaw(ParsePattern.encode(parsePatterns));
    }

    public void setParsePatternsRaw(String patternsRaw) {
        mClient.putString(PREF_PARSE_PATTERNS, patternsRaw);
    }

    public List<ParsePattern> getParsePatterns() {
        return ParsePattern.decode(getParsePatternsRaw());
    }

    /**
     * Get the current AI trigger keyword/symbol (the one configured for PatternType.CommandAI).
     *
     * This is used as the default trigger for roles when a role-specific trigger is not set.
     */
    public String getAiTriggerSymbol() {
        try {
            List<ParsePattern> patterns = getParsePatterns();
            if (patterns != null) {
                for (ParsePattern p : patterns) {
                    if (p == null) continue;
                    if (p.getType() != PatternType.CommandAI) continue;
                    String sym = null;
                    try {
                        sym = PatternType.regexToSymbol(p.getPattern().pattern());
                    } catch (Throwable ignored) {
                    }
                    if (sym != null) sym = sym.trim();
                    if (sym != null && !sym.isEmpty()) return sym;

                    // Pattern exists but we couldn't extract the symbol for some reason.
                    // Fall back to the type default.
                    return PatternType.CommandAI.defaultSymbol;
                }
            }
        } catch (Throwable ignored) {}
        return PatternType.CommandAI.defaultSymbol;
    }

    public String getParsePatternsRaw() {
        return mClient.getString(PREF_PARSE_PATTERNS, null);
    }

    
    // ===== AI Trigger (multiline prompt sending) =====
    // True: send full text (including newlines) when AI trigger symbol is used.
    // False: send only the last line (approximation of "cursor line").
    private static final String PREF_AI_TRIGGER_MULTILINE = "ai_trigger_multiline_enabled_v1";

    public boolean getAiTriggerMultilineEnabled() {
        return mClient.getBoolean(PREF_AI_TRIGGER_MULTILINE, true); // default ON
    }

    public void setAiTriggerMultilineEnabled(boolean enabled) {
        mClient.putBoolean(PREF_AI_TRIGGER_MULTILINE, enabled);
    }

    // ===== Invocation master switches =====
    // Master toggles to quickly enable/disable all invocation commands or triggers.
    // They do NOT change the per-command/per-trigger configuration; they only gate runtime parsing.
    private static final String PREF_INVOCATION_COMMANDS_ENABLED = "invocation_commands_enabled_v1";
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED = "invocation_triggers_enabled_v1";
    // Backup of per-trigger enabled states for the "master switch" bulk toggle (restore previous states).
    private static final String PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP = "invocation_triggers_enabled_states_backup_v1";

    public boolean getInvocationCommandsEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_COMMANDS_ENABLED, true);
    }

    public void setInvocationCommandsEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_COMMANDS_ENABLED, enabled);
    }

    public boolean getInvocationTriggersEnabled() {
        return mClient.getBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, true);
    }

    public void setInvocationTriggersEnabled(boolean enabled) {
        mClient.putBoolean(PREF_INVOCATION_TRIGGERS_ENABLED, enabled);
    }

    /**
     * Bulk-disable all invocation triggers while saving a snapshot of each trigger's enabled state.
     * This allows restoring previous states later (master switch ON).
     */
    public void disableAllInvocationTriggersWithBackup() {
        try {
            // IMPORTANT:
            // Do not overwrite the backup snapshot if the master switch is already OFF.
            // Otherwise, repeated "disable" calls can save an "all disabled" snapshot,
            // and later restore will keep everything disabled.
            boolean masterWasEnabled = true;
            try { masterWasEnabled = getInvocationTriggersEnabled(); } catch (Throwable ignored) {}

            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(false);
                return;
            }

            // Already disabled: never overwrite the existing snapshot (if any).
            // If there is no snapshot, we still avoid creating an "all disabled" snapshot.
            if (!masterWasEnabled) {
                boolean changed = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) {
                        p.setEnabled(false);
                        changed = true;
                    }
                }
                if (changed) setParsePatterns(ps);
                setInvocationTriggersEnabled(false);
                return;
            }
            // Save snapshot
            JSONArray arr = new JSONArray();
            for (ParsePattern p : ps) {
                if (p == null || p.getType() == null) continue;
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("type", p.getType().name());
                o.put("enabled", p.isEnabled());
                arr.put(o);
            }
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, arr.toString());
            // Disable all
            for (ParsePattern p : ps) {
                if (p == null) continue;
                p.setEnabled(false);
            }
            setParsePatterns(ps);
            // Keep the UI switch state
            setInvocationTriggersEnabled(false);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Restore invocation triggers enabled states from the last saved snapshot (if any).
     * If there is no snapshot, it simply marks the master switch as enabled.
     */
    public void restoreInvocationTriggersFromBackup() {
        try {
            String raw = mClient.getString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, null);
            List<ParsePattern> ps = getParsePatterns();
            if (ps == null) {
                setInvocationTriggersEnabled(true);
                return;
            }
            java.util.HashMap<String, Boolean> map = new java.util.HashMap<>();
            if (raw != null && !raw.isEmpty()) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        String type = o.optString("type", null);
                        if (type == null) continue;
                        map.put(type, o.optBoolean("enabled", true));
                    } catch (Throwable ignored) {}
                }
            }

            // Heuristic recovery:
            // If the snapshot exists but ALL saved values are "false", it is very likely the snapshot
            // was overwritten while everything was already disabled (a known bug scenario).
            // Treat this as "no snapshot" so we can recover to a usable state.
            if (!map.isEmpty()) {
                boolean anyTrue = false;
                for (Boolean b : map.values()) {
                    if (b != null && b) { anyTrue = true; break; }
                }
                if (!anyTrue) {
                    map.clear();
                }
            }
            boolean restoredAny = false;
            if (!map.isEmpty()) {
                for (ParsePattern p : ps) {
                    if (p == null || p.getType() == null) continue;
                    Boolean en = map.get(p.getType().name());
                    if (en != null) {
                        p.setEnabled(en);
                        restoredAny = true;
                    }
                }
                if (restoredAny) setParsePatterns(ps);
            }

            // Fallback: if we have no snapshot (or it was empty) and ALL patterns are disabled,
            // re-enable them to avoid leaving the user in a "stuck" state.
            if (!restoredAny) {
                boolean anyEnabled = false;
                for (ParsePattern p : ps) {
                    if (p == null) continue;
                    if (p.isEnabled()) { anyEnabled = true; break; }
                }
                if (!anyEnabled) {
                    for (ParsePattern p : ps) {
                        if (p == null) continue;
                        p.setEnabled(true);
                    }
                    setParsePatterns(ps);
                }
            }
            // Clear snapshot after restore
            mClient.putString(PREF_INVOCATION_TRIGGERS_ENABLED_STATES_BACKUP, "");
            setInvocationTriggersEnabled(true);
        } catch (Throwable ignored) {
            try { setInvocationTriggersEnabled(true); } catch (Throwable ignored2) {}
        }
    }


    private void updateGenerativeAICommands() {
        String raw = mClient.getString(PREF_GEN_AI_COMMANDS, "[]");
        generativeAICommands = Collections.unmodifiableList(Commands.decodeCommands(raw));
    }

    @Override
    public Bundle getConfigBundle() {
        Bundle bundle = new Bundle();
        for (LanguageModel model : LanguageModel.values()) {
            Bundle configBundle = new Bundle();
            for (LanguageModelField field : LanguageModelField.values()) {
                configBundle.putString(field.name, getLanguageModelField(model, field));
            }
            bundle.putBundle(model.name(), configBundle);
        }
        return bundle;
    }

    @Override
    public Bundle getOtherSettings() {
        Bundle otherSettings = new Bundle();
        for (OtherSettingsType type : OtherSettingsType.values()) {
            switch (type.nature) {
                case Boolean:
                    otherSettings.putBoolean(type.name(), (Boolean) getOtherSetting(type));
                    break;
                case String:
                    otherSettings.putString(type.name(), (String) getOtherSetting(type));
                    break;
                case Integer:
                    otherSettings.putInt(type.name(), (Integer) getOtherSetting(type));
                    break;
            }
        }
        return otherSettings;
    }

    public void setOtherSetting(OtherSettingsType type, Object value) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                mClient.putBoolean(key, (Boolean) value);
                break;
            case String:
                mClient.putString(key, (String) value);
                break;
            case Integer:
                mClient.putInt(key, (Integer) value);
                break;
        }
    }

    public Object getOtherSetting(OtherSettingsType type) {
        String key = String.format(PREF_OTHER_SETTING, type.name());
        switch (type.nature) {
            case Boolean:
                return mClient.getBoolean(key, (Boolean) type.defaultValue);
            case String:
                return mClient.getString(key, (String) type.defaultValue);
            case Integer:
                return mClient.getInt(key, (Integer) type.defaultValue);
            default:
                return type.defaultValue;
        }
    }

    public Boolean getEnableLogs() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableLogs);
    }

    public Boolean getEnableExternalInternet() {
        return (Boolean) getOtherSetting(OtherSettingsType.EnableExternalInternet);
    }

    public void setSearchEngine(String searchEngine) {
        setOtherSetting(OtherSettingsType.SearchEngine, searchEngine);
    }

    public String getSearchEngine() {
        return (String) getOtherSetting(OtherSettingsType.SearchEngine);
    }

    public String getSearchUrl(String query) {
        String engine = getSearchEngine();
        return buildSearchUrl(engine, query);
    }

    // Update Settings
    public boolean getUpdateCheckEnabled() {
        return (Boolean) getOtherSetting(OtherSettingsType.UpdateCheckEnabled);
    }

    public void setUpdateCheckEnabled(boolean enabled) {
        setOtherSetting(OtherSettingsType.UpdateCheckEnabled, enabled);
    }

    public int getUpdateCheckInterval() {
        return (Integer) getOtherSetting(OtherSettingsType.UpdateCheckInterval);
    }

    public void setUpdateCheckInterval(int hours) {
        setOtherSetting(OtherSettingsType.UpdateCheckInterval, hours);
    }

    public String getUpdateDownloadPath() {
        return (String) getOtherSetting(OtherSettingsType.UpdateDownloadPath);
    }

    // -----------------------------
    // Quick Jump (deep-link templates)
    // -----------------------------
    public String getQuickJumpConfig() {
        return mClient.getString(PREF_QUICK_JUMP_CONFIG, "");
    }

    public void setQuickJumpConfig(String value) {
        if (value == null) value = "";
        mClient.putString(PREF_QUICK_JUMP_CONFIG, value);
    }

    public void setUpdateDownloadPath(String path) {
        setOtherSetting(OtherSettingsType.UpdateDownloadPath, path);
    }

    public static String getSearchUrlFromKGPT(Context context, String query) {
        // IMPORTANT:
        // This method is used from places where SPManager may not be fully initialized
        // (e.g. Xposed / different process). Do NOT hardcode an engine here.
        // Always try to read the latest preference from the shared ConfigProvider.
        String engine = "duckduckgo";
        try {
            if (context != null) {
                // Same key used by setOtherSetting(OtherSettingsType.SearchEngine, ...)
                tn.eluea.kgpt.provider.ConfigClient client = new tn.eluea.kgpt.provider.ConfigClient(context);
                String key = String.format(PREF_OTHER_SETTING, tn.eluea.kgpt.settings.OtherSettingsType.SearchEngine.name());
                String v = client.getString(key, "duckduckgo");
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            } else if (SPManager.isReady()) {
                String v = SPManager.getInstance().getSearchEngine();
                if (v != null && !v.trim().isEmpty()) engine = v.trim();
            }
        } catch (Throwable ignored) {
            // fall back to default
        }
        return buildSearchUrl(engine, query);
    }

    private static String buildSearchUrl(String engine, String query) {
        String encodedQuery;
        try {
            encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            encodedQuery = query;
        }

        switch (engine) {
            case "google":
                return "https://www.google.com/search?q=" + encodedQuery;
            case "bing":
                return "https://www.bing.com/search?q=" + encodedQuery;
            case "yahoo":
                return "https://search.yahoo.com/search?p=" + encodedQuery;
            case "yandex":
                return "https://yandex.com/search/?text=" + encodedQuery;
            case "brave":
                return "https://search.brave.com/search?q=" + encodedQuery;
            case "ecosia":
                return "https://www.ecosia.org/search?q=" + encodedQuery;
            case "qwant":
                return "https://www.qwant.com/?q=" + encodedQuery;
            case "startpage":
                return "https://www.startpage.com/do/dsearch?query=" + encodedQuery;
            case "perplexity":
                return "https://www.perplexity.ai/?q=" + encodedQuery;
            case "phind":
                return "https://www.phind.com/search?q=" + encodedQuery;
            case "duckduckgo":
            default:
                return "https://duckduckgo.com/?q=" + encodedQuery;
        }
    }

    /**
     * Register a listener for config changes
     */
    public void registerConfigChangeListener(ConfigClient.OnConfigChangeListener listener) {
        mClient.registerGlobalListener(listener);
    }

    /**
     * Get the underlying ConfigClient for advanced usage
     */
    public ConfigClient getConfigClient() {
        return mClient;
    }

    public boolean isAmoledTheme() {
        // Use raw key "amoled_mode" to match SettingsFragment implementation
        return mClient.getBoolean("amoled_mode", false);
    }

    // ===== AI Clipboard Groups =====
    private static final String PREF_AI_CLIPBOARD_GROUPS_JSON = "ai_clipboard.groups.json.v1";
    private static final int AI_CLIPBOARD_MAX_GROUPS = 10;

    /** Returns the user-created clipboard groups (max 10). */
    public List<String> getAiClipboardGroups() {
        String raw = mClient.getString(PREF_AI_CLIPBOARD_GROUPS_JSON, "[]");
        if (raw == null || raw.trim().isEmpty()) raw = "[]";

        ArrayList<String> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (seen.add(key)) {
                    out.add(v);
                    if (out.size() >= AI_CLIPBOARD_MAX_GROUPS) break;
                }
            }
        } catch (JSONException ignored) {}

        return out;
    }

    /** Overwrites the user-created clipboard groups (max 10). */
    public void setAiClipboardGroups(List<String> groups) {
        JSONArray arr = new JSONArray();
        HashSet<String> seen = new HashSet<>();

        if (groups != null) {
            for (String s : groups) {
                if (s == null) continue;
                String v = s.trim();
                if (v.isEmpty()) continue;
                String key = v.toLowerCase();
                if (!seen.add(key)) continue;
                arr.put(v);
                if (arr.length() >= AI_CLIPBOARD_MAX_GROUPS) break;
            }
        }

        mClient.putString(PREF_AI_CLIPBOARD_GROUPS_JSON, arr.toString());
    }


}