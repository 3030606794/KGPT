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
package tn.eluea.kgpt.text;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tn.eluea.kgpt.SPManager;
import tn.eluea.kgpt.core.quickjump.QuickJumpEntry;
import tn.eluea.kgpt.core.quickjump.QuickJumpManager;
import tn.eluea.kgpt.text.parse.result.QuickJumpParseResult;
import tn.eluea.kgpt.listener.ConfigChangeListener;
import tn.eluea.kgpt.llm.LanguageModel;
import tn.eluea.kgpt.llm.LanguageModelField;
import tn.eluea.kgpt.text.parse.ParsePattern;
import tn.eluea.kgpt.text.parse.PatternType;
import tn.eluea.kgpt.features.textactions.TextActionCommands;
import tn.eluea.kgpt.roles.RoleManager;
import tn.eluea.kgpt.text.parse.result.InlineAskParseResult;
import tn.eluea.kgpt.text.parse.result.InlineAskParseResultFactory;
import tn.eluea.kgpt.text.parse.result.InlineCommandParseResult;
import tn.eluea.kgpt.text.parse.result.InlineCommandParseResultFactory;
import tn.eluea.kgpt.text.parse.result.CommandParseResult;
import tn.eluea.kgpt.text.parse.result.ParseResultFactory;
import tn.eluea.kgpt.text.parse.ParseDirective;
import tn.eluea.kgpt.text.parse.result.ParseResult;
import tn.eluea.kgpt.text.parse.result.AIParseResult;
import tn.eluea.kgpt.text.parse.result.AppTriggerParseResult;
import tn.eluea.kgpt.text.parse.result.TextActionParseResult;
import tn.eluea.kgpt.ui.UiInteractor;
import tn.eluea.kgpt.ui.lab.apptrigger.AppTrigger;
import tn.eluea.kgpt.ui.lab.apptrigger.AppTriggerManager;
import tn.eluea.kgpt.instruction.command.GenerativeAICommand;

public class TextParser implements ConfigChangeListener {
    private final List<ParseDirective> directives = new ArrayList<>();
    private String currentTriggerSymbol = "$";
    private boolean aiTriggerEnabled = false;
    private boolean textActionsEnabled = false;
    private AppTriggerManager appTriggerManager = null;
    private java.util.Set<String> availableCommands = new java.util.HashSet<>();

    // Master (one-click) toggles cached in-process.
    // We refresh periodically (and also listen to ConfigClient) to ensure the IME process
    // picks up changes immediately without requiring a force-stop/restart.
    private static final String KEY_INVOCATION_COMMANDS_ENABLED = "invocation_commands_enabled_v1";
    private static final String KEY_INVOCATION_TRIGGERS_ENABLED = "invocation_triggers_enabled_v1";
    private static final long MASTER_REFRESH_INTERVAL_MS = 200;
    private volatile boolean masterCommandsEnabled = true;
    private volatile boolean masterTriggersEnabled = true;
    private long lastMasterRefreshMs = 0;

    // Quick Jump entries cache (parsed from SPManager.getQuickJumpConfig())
    private String quickJumpCacheRaw = null;
    private List<QuickJumpEntry> quickJumpCacheItems = Collections.emptyList();


    public TextParser() {
        UiInteractor.getInstance().registerConfigChangeListener(this);
        List<ParsePattern> parsePatterns = SPManager.getInstance().getParsePatterns();
        updatePatterns(parsePatterns);
        loadAvailableCommands();

        // Initial read + live updates for master toggles
        refreshMasterSwitches(true);
        try {
            SPManager.getInstance().getConfigClient().registerListener(KEY_INVOCATION_TRIGGERS_ENABLED,
                    (key, newValue) -> masterTriggersEnabled = parseBoolean(newValue, true));
            SPManager.getInstance().getConfigClient().registerListener(KEY_INVOCATION_COMMANDS_ENABLED,
                    (key, newValue) -> masterCommandsEnabled = parseBoolean(newValue, true));
        } catch (Throwable ignored) {
        }
    }

    private static boolean parseBoolean(Object v, boolean def) {
        if (v == null) return def;
        try {
            return Boolean.parseBoolean(String.valueOf(v));
        } catch (Throwable t) {
            return def;
        }
    }

    private void refreshMasterSwitches(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && (now - lastMasterRefreshMs) < MASTER_REFRESH_INTERVAL_MS) return;
        lastMasterRefreshMs = now;
        try {
            // Bypass cache to avoid stale values in some IME/Xposed process environments.
            masterTriggersEnabled = SPManager.getInstance().getConfigClient()
                    .getBooleanNoCache(KEY_INVOCATION_TRIGGERS_ENABLED, true);
            masterCommandsEnabled = SPManager.getInstance().getConfigClient()
                    .getBooleanNoCache(KEY_INVOCATION_COMMANDS_ENABLED, true);
        } catch (Throwable ignored) {
        }
    }

    private void loadAvailableCommands() {
        availableCommands.clear();
        List<GenerativeAICommand> commands = SPManager.getInstance().getGenerativeAICommands();
        for (GenerativeAICommand cmd : commands) {
            availableCommands.add(cmd.getCommandPrefix());
        }
    }

    public void setAppTriggerManager(AppTriggerManager manager) {
        this.appTriggerManager = manager;
    }

    /**
     * Set whether text actions are enabled.
     */
    public void setTextActionsEnabled(boolean enabled) {
        this.textActionsEnabled = enabled;
    }

    private void updatePatterns(List<ParsePattern> parsePatterns) {
        directives.clear();
        aiTriggerEnabled = false;

        for (ParsePattern parsePattern : parsePatterns) {
            // Only add enabled patterns
            if (parsePattern.isEnabled()) {
                directives.add(new ParseDirective(parsePattern.getPattern(),
                        ParseResultFactory.of(parsePattern.getType())));
            }

            // Track AI trigger symbol and enabled state
            if (parsePattern.getType() == PatternType.CommandAI) {
                String symbol = PatternType.regexToSymbol(parsePattern.getPattern().pattern());
                if (symbol != null && !symbol.isEmpty()) {
                    currentTriggerSymbol = symbol;
                }
                aiTriggerEnabled = parsePattern.isEnabled();
            }
        }
    }
    private ParseResult adjustAiTriggerToCursorLineIfNeeded(
            ParseResult original,
            String fullText,
            int cursor,
            String triggerSymbol
    ) {
        try {
            if (!(original instanceof AIParseResult)) return original;
            AIParseResult origAi = (AIParseResult) original;
            if (SPManager.getInstance().getAiTriggerMultilineEnabled()) return original;
            if (fullText == null) return original;
            cursor = Math.max(0, Math.min(cursor, fullText.length()));
            if (triggerSymbol == null || triggerSymbol.isEmpty()) return original;

            String before = fullText.substring(0, cursor);
            if (!before.endsWith(triggerSymbol)) return original;

            int lf = before.lastIndexOf('\n');
            int cr = before.lastIndexOf('\r');
            int sep = Math.max(lf, cr);
            int lineStart = sep >= 0 ? sep + 1 : 0;

            String lineBeforeCursor = before.substring(lineStart);
            if (!lineBeforeCursor.endsWith(triggerSymbol)) return original;

            String prompt = lineBeforeCursor.substring(0, lineBeforeCursor.length() - triggerSymbol.length()).trim();

            java.util.ArrayList<String> groups = new java.util.ArrayList<>();
            groups.add(lineBeforeCursor); // group0
            groups.add(prompt);          // group1

            return new AIParseResult(groups, lineStart, cursor, origAi.roleIdOverride, triggerSymbol);
        } catch (Throwable t) {
            return original;
        }
    }

    /**
     * Role-specific AI triggers.
     *
     * If a role has a custom trigger keyword, typing: <text><trigger> invokes AI using that role.
     *
     * IMPORTANT BEHAVIOR:
     * - Roles with an EMPTY trigger do NOT participate in role auto-selection.
     *   They simply "inherit" the global AI trigger keyword for invoking AI,
     *   and the ACTIVE role (the one checked in the UI) will be used.
     *
     * This avoids ambiguous situations where multiple roles would share the
     * same global trigger keyword (e.g., many roles left blank).
     */
    private AIParseResult checkRoleAiTrigger(String textBeforeCursor, String fullText, int cursor) {
        try {
            if (!aiTriggerEnabled) return null;
            if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return null;

            SPManager sp = SPManager.getInstance();
            if (sp == null) return null;

            String rolesJson = sp.getRolesJson();
            List<RoleManager.Role> roles = RoleManager.loadRoles(rolesJson);
            if (roles == null || roles.isEmpty()) return null;

            // Find matching triggers (longest suffix wins).
            String bestTrigger = null;
            java.util.ArrayList<RoleManager.Role> bestRoles = new java.util.ArrayList<>();

            for (RoleManager.Role r : roles) {
                if (r == null) continue;
                // Only role-specific (explicit) triggers should override the active role.
                // Empty trigger means "use the global AI trigger" and should NOT auto-select a role.
                String trig = r.trigger != null ? r.trigger.trim() : "";
                if (trig.isEmpty()) continue;

                if (textBeforeCursor.endsWith(trig)) {
                    if (bestTrigger == null || trig.length() > bestTrigger.length()) {
                        bestTrigger = trig;
                        bestRoles.clear();
                        bestRoles.add(r);
                    } else if (trig.length() == bestTrigger.length() && trig.equals(bestTrigger)) {
                        bestRoles.add(r);
                    }
                }
            }

            if (bestTrigger == null || bestRoles.isEmpty()) return null;

            // Choose role:
            // If multiple roles share the same explicit trigger, prefer the current active role.
            RoleManager.Role chosen = null;
            String activeId = sp.getActiveRoleId();
            for (RoleManager.Role r : bestRoles) {
                if (r == null) continue;
                if (activeId != null && activeId.equals(r.id)) {
                    chosen = r;
                    break;
                }
            }
            if (chosen == null) chosen = bestRoles.get(0);

            if (chosen == null) return null;
            String roleId = chosen.id != null ? chosen.id.trim() : RoleManager.DEFAULT_ROLE_ID;
            if (roleId.isEmpty()) roleId = RoleManager.DEFAULT_ROLE_ID;

            // Build groups consistent with PatternType.CommandAI: group0 = full match, group1 = prompt
            String promptRaw = textBeforeCursor.substring(0, Math.max(0, textBeforeCursor.length() - bestTrigger.length()));
            java.util.ArrayList<String> groups = new java.util.ArrayList<>();
            groups.add(textBeforeCursor); // group0
            groups.add(promptRaw);        // group1

            AIParseResult res = new AIParseResult(groups, 0, cursor, roleId, bestTrigger);
            ParseResult adjusted = adjustAiTriggerToCursorLineIfNeeded(res, fullText, cursor, bestTrigger);
            return (adjusted instanceof AIParseResult) ? (AIParseResult) adjusted : res;
        } catch (Throwable t) {
            return null;
        }
    }


    /**
     * Quick Jump trigger: user-defined URL templates with keyword triggers.
     * When the input ends with a matching trigger, we delete only the trigger
     * (optionally one preceding whitespace) and open the URL externally.
     */
    private QuickJumpParseResult checkQuickJump(String textBeforeCursor, int cursor) {
        try {
            if (textBeforeCursor == null || textBeforeCursor.isEmpty()) return null;

            SPManager sp = SPManager.getInstance();
            if (sp == null) return null;

            String cfg = null;
            try { cfg = sp.getQuickJumpConfig(); } catch (Throwable ignored) {}
            if (cfg == null) cfg = "";

            if (quickJumpCacheRaw == null || !quickJumpCacheRaw.equals(cfg)) {
                quickJumpCacheRaw = cfg;
                try {
                    quickJumpCacheItems = QuickJumpManager.load(cfg);
                } catch (Throwable t) {
                    quickJumpCacheItems = java.util.Collections.emptyList();
                }
            }

            if (quickJumpCacheItems == null || quickJumpCacheItems.isEmpty()) return null;

            int len = textBeforeCursor.length();
            int trimmedEnd = len;
            while (trimmedEnd > 0 && Character.isWhitespace(textBeforeCursor.charAt(trimmedEnd - 1))) {
                trimmedEnd--;
            }
            if (trimmedEnd <= 0) return null;

            String core = textBeforeCursor.substring(0, trimmedEnd);
            if (core.isEmpty()) return null;

            QuickJumpEntry best = null;
            String bestTrig = null;
            for (QuickJumpEntry e : quickJumpCacheItems) {
                if (e == null || !e.enabled) continue;
                String trig = e.trigger != null ? e.trigger.trim() : "";
                if (trig.isEmpty()) continue;

                if (core.endsWith(trig)) {
                    if (bestTrig == null || trig.length() > bestTrig.length()) {
                        bestTrig = trig;
                        best = e;
                    }
                }
            }

            if (best == null || bestTrig == null) return null;

            int triggerEnd = trimmedEnd;
            int triggerStart = triggerEnd - bestTrig.length();
            if (triggerStart < 0) return null;

            // Remove a single whitespace char before the trigger (common usage: "query gg")
            if (triggerStart > 0 && Character.isWhitespace(textBeforeCursor.charAt(triggerStart - 1))) {
                triggerStart = Math.max(0, triggerStart - 1);
            }

            String queryRaw = textBeforeCursor.substring(0, triggerStart);

            // Only use the current line text as the query.
            // Otherwise, previous lines (e.g., other commands / URLs in the document)
            // can be included and pollute the search query.
            if (queryRaw != null) {
                int ln = Math.max(queryRaw.lastIndexOf('\n'), queryRaw.lastIndexOf('\r'));
                if (ln >= 0 && ln + 1 < queryRaw.length()) {
                    queryRaw = queryRaw.substring(ln + 1);
                }
            }

            String query = queryRaw != null ? queryRaw.trim() : "";

            String url = QuickJumpManager.buildUrl(best.urlTemplate, query);

            ArrayList<String> groups = new ArrayList<>();
            groups.add(textBeforeCursor);
            groups.add(query);

            // Delete only the trigger (and optional preceding space), keep the query text.
            return new QuickJumpParseResult(groups, triggerStart, cursor,
                    best.name != null ? best.name : "",
                    bestTrig,
                    query,
                    url);
        } catch (Throwable t) {
            return null;
        }
    }
    public ParseResult parse(String text, int cursor) {
        // Bounds check to prevent StringIndexOutOfBoundsException
        if (text == null || text.isEmpty()) {
            return null;
        }
        cursor = Math.max(0, Math.min(cursor, text.length()));

        String textBeforeCursor = text.substring(0, cursor);

        // Master switches (UI one-click toggles)
        // NOTE: These are intentionally refreshed frequently because some ROMs may miss
        // ContentObserver updates in the IME/Xposed process.
        refreshMasterSwitches(false);
        final boolean commandsEnabled = masterCommandsEnabled;
        final boolean triggersEnabled = masterTriggersEnabled;

        // Quick Jump (user-defined URL triggers)
        QuickJumpParseResult qj = checkQuickJump(textBeforeCursor, cursor);
        if (qj != null) {
            return qj;
        }

        // Check for app triggers first (if enabled)
        android.util.Log.d("KGPT_AppTrigger", "parse() called with text: '" + textBeforeCursor + "'");
        AppTriggerParseResult appTriggerResult = checkAppTrigger(textBeforeCursor);
        if (appTriggerResult != null) {
            android.util.Log.d("KGPT_AppTrigger",
                    "Found trigger: " + appTriggerResult.trigger + " -> " + appTriggerResult.packageName);
            return appTriggerResult;
        }

        // Check for text action commands (e.g., "text $rephrase")
        TextActionParseResult textActionResult = checkTextAction(textBeforeCursor);
        if (textActionResult != null) {
            return textActionResult;
        }

        // If invocation triggers are globally disabled, stop here.
        // (Quick Jump / App Triggers / Text Actions are handled above.)
        if (!triggersEnabled) {
            return null;
        }

        // Check for role-specific AI triggers (e.g., "hello es" where "es" is the role trigger)
        // Do this before regex directives so it can work even when the trigger isn't a configured ParsePattern.
        AIParseResult roleAi = checkRoleAiTrigger(textBeforeCursor, text, cursor);
        if (roleAi != null) {
            return roleAi;
        }

        // Only check inline ask/inline commands if AI trigger is enabled
        if (aiTriggerEnabled) {
            // Check for inline commands first (any /command with preserved text)
            // These handle their own text preservation
            if (commandsEnabled) {
                InlineCommandParseResult inlineCommandResult = InlineCommandParseResultFactory.parse(
                        textBeforeCursor, currentTriggerSymbol, availableCommands);
                if (inlineCommandResult != null) {
                    return inlineCommandResult;
                }
            }

            // Check for /ask usage as a shield for ANY directive
            // This fixes the issue where valid triggers (like @ for bold) would apply to
            // the entire text
            // because they matched the whole string pattern. /ask now properly delimits the
            // scope.
            String prefix = tn.eluea.kgpt.instruction.command.InlineAskCommand.getPrefix();
            String askPatternStr = "/" + java.util.regex.Pattern.quote(prefix) + "\\s+";
            java.util.regex.Pattern askPattern = java.util.regex.Pattern.compile(askPatternStr);
            java.util.regex.Matcher askMatcher = askPattern.matcher(textBeforeCursor);

            int lastAskIndex = -1;
            int lastContentStart = -1;

            // Find the *last* occurrence of /ask followed by whitespace
            while (askMatcher.find()) {
                lastAskIndex = askMatcher.start();
                lastContentStart = askMatcher.end();
            }

            if (commandsEnabled && lastAskIndex >= 0) {
                String scopedText = textBeforeCursor.substring(lastContentStart);

                // Check if this scoped text matches any directive
                for (ParseDirective directive : directives) {
                    // Pass 'lastAskIndex' as startOverride so the Result consumes the "/ask ..."
                    // part
                    // Pass 'lastContentStart' as offset for the scoped text
                    ParseResult result = directive.parseWithStartOverride(scopedText, lastContentStart, lastAskIndex);
                    if (result != null) {
                        // When commands are disabled, ignore command results (including "%"-based commands).
                        if (!commandsEnabled && (result instanceof CommandParseResult)) {
                            continue;
                        }
                        return adjustAiTriggerToCursorLineIfNeeded(result, text, cursor, currentTriggerSymbol);
                    }
                }
            }

            // Fallback to strict InlineAskParseResultFactory if generic shielding didn't
            // match anything
            // This handles cases specific to the Factory implementation if any
            if (commandsEnabled) {
                InlineAskParseResult inlineAskResult = InlineAskParseResultFactory.parse(
                        textBeforeCursor, currentTriggerSymbol);
                if (inlineAskResult != null) {
                    return inlineAskResult;
                }
            }
        }

        for (ParseDirective directive : directives) {
            ParseResult parseResult = directive.parse(textBeforeCursor);
            if (parseResult != null) {
                // When commands are globally disabled, ignore command parse results.
                if (!commandsEnabled && (parseResult instanceof CommandParseResult)) {
                    continue;
                }
                return adjustAiTriggerToCursorLineIfNeeded(parseResult, text, cursor, currentTriggerSymbol);
            }
        }

        return null;
    }

    /**
     * Check if the text ends with an app trigger
     */
    private AppTriggerParseResult checkAppTrigger(String text) {
        android.util.Log.d("KGPT_AppTrigger", "checkAppTrigger() - appTriggerManager: " + (appTriggerManager != null));

        if (appTriggerManager == null || !appTriggerManager.isFeatureEnabled()) {
            android.util.Log.d("KGPT_AppTrigger", "Feature disabled or manager null. Enabled: " +
                    (appTriggerManager != null ? appTriggerManager.isFeatureEnabled() : "null"));
            return null;
        }

        List<AppTrigger> triggers = appTriggerManager.getAppTriggers();
        android.util.Log.d("KGPT_AppTrigger", "Loaded " + triggers.size() + " triggers");
        for (AppTrigger t : triggers) {
            android.util.Log.d("KGPT_AppTrigger", "  - Trigger: '" + t.getTrigger() + "' enabled: " + t.isEnabled());
        }

        if (triggers.isEmpty()) {
            return null;
        }

        // Don't process empty text
        if (text == null || text.isEmpty()) {
            return null;
        }

        String trimmedText = text.trim();
        if (trimmedText.isEmpty()) {
            return null;
        }

        // Check if the trimmed text ends with any trigger
        // This handles both "trigger" and "trigger " cases
        String lowerTrimmed = trimmedText.toLowerCase(java.util.Locale.ROOT);
        android.util.Log.d("KGPT_AppTrigger", "Checking text: '" + lowerTrimmed + "'");

        for (AppTrigger trigger : triggers) {
            if (!trigger.isEnabled()) {
                continue;
            }

            String triggerText = trigger.getTrigger().toLowerCase(java.util.Locale.ROOT);
            android.util.Log.d("KGPT_AppTrigger", "Comparing with trigger: '" + triggerText + "'");

            boolean match = false;
            if (lowerTrimmed.equals(triggerText)) {
                match = true;
            } else if (lowerTrimmed.endsWith(triggerText)) {
                // Check word boundary
                char charBefore = lowerTrimmed.charAt(lowerTrimmed.length() - triggerText.length() - 1);
                if (!Character.isLetterOrDigit(charBefore)) {
                    match = true;
                }
            }

            if (match) {
                android.util.Log.d("KGPT_AppTrigger", "MATCH FOUND! trigger: " + triggerText);

                // Find the actual position in original text
                int triggerStartInTrimmed = trimmedText.length() - trigger.getTrigger().length();

                // Find where trimmed text starts in original
                int trimStart = 0;
                while (trimStart < text.length() && Character.isWhitespace(text.charAt(trimStart))) {
                    trimStart++;
                }

                int wordStart = trimStart + triggerStartInTrimmed;

                // Return result that removes from word start to end of text
                return new AppTriggerParseResult(
                        java.util.Collections.singletonList(trigger.getTrigger()),
                        wordStart,
                        text.length(),
                        trigger.getTrigger(),
                        trigger.getPackageName(),
                        trigger.getActivityName(),
                        trigger.getAppName());
            }
        }

        android.util.Log.d("KGPT_AppTrigger", "No match found");
        return null;
    }

    /**
     * Check if the text ends with a text action command
     */
    private TextActionParseResult checkTextAction(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Parse the text for action commands
        TextActionCommands.ParseResult result = TextActionCommands.parse(text);
        if (result != null) {
            android.util.Log.d("KGPT_TextAction",
                    "Found action: " + result.action.name() + " for text: " + result.text);
            return new TextActionParseResult(
                    Collections.singletonList(result.text),
                    result.startIndex,
                    result.endIndex,
                    result.text,
                    result.action);
        }

        return null;
    }

    @Override
    public void onLanguageModelChange(LanguageModel model) {
    }

    @Override
    public void onLanguageModelFieldChange(LanguageModel model, LanguageModelField field, String value) {
    }

    @Override
    public void onCommandsChange(String commandsRaw) {
        loadAvailableCommands();
    }

    @Override
    public void onPatternsChange(String patternsRaw) {
        updatePatterns(ParsePattern.decode(patternsRaw));
    }

    @Override
    public void onOtherSettingsChange(Bundle otherSettings) {
    }
}
