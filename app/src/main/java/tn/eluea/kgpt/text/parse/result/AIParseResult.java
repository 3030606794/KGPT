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
package tn.eluea.kgpt.text.parse.result;

import java.util.List;

public class AIParseResult extends ParseResult {
    public final String prompt;

    /**
     * Optional role id override (persona) to apply for this request.
     * If null/blank, the currently active role is used.
     */
    public final String roleIdOverride;

    /**
     * The trigger symbol/keyword that was used to invoke AI.
     * Mainly used for correct single-line deletion behavior.
     */
    public final String triggerSymbol;

    public AIParseResult(List<String> groups, int indexStart, int indexEnd) {
        this(groups, indexStart, indexEnd, null, null);
    }

    public AIParseResult(List<String> groups, int indexStart, int indexEnd, String roleIdOverride, String triggerSymbol) {
        super(groups, indexStart, indexEnd);

        String p = "";
        try {
            if (groups != null && groups.size() > 1 && groups.get(1) != null) {
                p = groups.get(1);
            }
        } catch (Throwable ignored) {}

        this.prompt = p == null ? "" : p.trim();

        this.roleIdOverride = (roleIdOverride == null || roleIdOverride.trim().isEmpty()) ? null : roleIdOverride.trim();
        this.triggerSymbol = (triggerSymbol == null || triggerSymbol.trim().isEmpty()) ? null : triggerSymbol;
    }
}
