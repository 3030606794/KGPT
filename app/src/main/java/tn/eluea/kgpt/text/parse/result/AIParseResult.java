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

    public AIParseResult(List<String> groups, int indexStart, int indexEnd) {
        super(groups, indexStart, indexEnd);

        String p = "";
        try {
            if (groups != null && groups.size() > 1 && groups.get(1) != null) {
                p = groups.get(1);
            }
        } catch (Throwable ignored) {}

        this.prompt = p == null ? "" : p.trim();
        }
}
