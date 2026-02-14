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
package tn.eluea.kgpt.text.parse;

public enum PatternType {
    Settings("设置", 0, "\\*#settings#\\*$", true, "*#settings#*", "设置触发器"),
    // Use [\s\S] instead of '.' so multi-line input is fully captured ('.' doesn't match newlines)
    CommandAI("AI 触发器", 1, "([\\s\\S]+)\\$$", true, "$", "输入文本并在末尾加 $"),
    CommandCustom("自定义指令", 2, "([^%]+)%(?:([^ %]+))?%$", true, "%", "输入文本并添加 %command%"),
    FormatItalic("斜体", 1, "([^|]+)\\|$", true, "|", "输入文本并在末尾加 |"),
    FormatBold("加粗", 1, "([^@]+)@$", true, "@", "输入文本并在末尾加 @"),
    FormatCrossout("删除线", 1, "([^~]+)~$", true, "~", "输入文本并在末尾加 ~"),
    FormatUnderline("下划线", 1, "([^_]+)_$", true, "_", "输入文本并在末尾加 _"),
    WebSearch("网页搜索", 1, "([\\s\\S]+)\\?\\?$", true, "??", "输入文本并在末尾加 ?? 进行搜索"),
    ModelSwitch("模型切换", 0, "模型切换\\s*$", true, "模型切换", "输入触发词弹出模型选择"),
    AIClipboard("AI剪贴板", 0, "AI剪贴板\\s*$", true, "AI剪贴板", "输入触发词弹出AI剪贴板"),
    QuickJumpMenu("快捷跳转", 0, "快捷跳转\\s*$", true, "快捷跳转", "输入触发词弹出快捷跳转"),
    ;

    public final String title;
    public final int groupCount;
    public final String defaultPattern;
    public final boolean editable;
    public final String defaultSymbol;
    public final String description;

    PatternType(String title, int groupCount, String defaultPattern, boolean editable, String defaultSymbol,
            String description) {
        this.title = title;
        this.groupCount = groupCount;
        this.defaultPattern = defaultPattern;
        this.editable = editable;
        this.defaultSymbol = defaultSymbol;
        this.description = description;
    }

    /**
     * Convert a user-friendly symbol to regex pattern
     * The new logic: text is written normally, symbol at the END triggers AI
     * IMPORTANT: Requires at least one character before the trigger symbol
     */
    public static String symbolToRegex(String symbol, int groupCount) {
        if (symbol == null || symbol.isEmpty()) {
            return null;
        }

        String escapedSymbol = escapeRegex(symbol);

        if (groupCount == 0) {
            // For Settings-like patterns: exact match of the symbol (allow trailing whitespace/newlines)
            // This fixes triggers that are words (e.g., "模型切换", "AI剪贴板") not firing when the editor keeps a trailing newline.
            return String.format("%s\\s*$", escapedSymbol);
        } else if (groupCount == 1) {
            // Use [\s\S] instead of '.' so multi-line input is fully captured.
            // This also fixes the case where only the current line (after last newline) was matched.
            return String.format("([\\s\\S]+)%s$", escapedSymbol);
        } else if (groupCount == 2) {
            // Pattern for custom commands: text<symbol><command><symbol> (symbol can be multi-character)
            // For 1-char symbols we keep the original stricter behavior; for multi-char we fall back to a safe generic regex.
            if (symbol.length() == 1) {
                String literalSymbol = escapeLiteralForCharClass(symbol);
                return String.format("([^%s]+)%s(?:([^ %s]+))?%s$", literalSymbol, escapedSymbol, literalSymbol, escapedSymbol);
            }
            // Non-greedy prompt capture; command is optional and matches non-whitespace characters.
            return String.format("([\\s\\S]+?)%s(?:([^\\s]+))?%s$", escapedSymbol, escapedSymbol);
        }
        return null;
    }

    /**
     * Extract the trigger symbol from a regex pattern
     */
    public static String regexToSymbol(String regex) {
        if (regex == null || regex.isEmpty()) {
            return null;
        }

        // For CommandCustom pattern: ([^%]+)%(?:([^ %]+))?%$ - extract the % symbol
        // Look for pattern like ([^X]+)X where X is the symbol
        java.util.regex.Pattern charClassPattern = java.util.regex.Pattern.compile("\\[\\^([^\\]]+)\\]");
        java.util.regex.Matcher matcher = charClassPattern.matcher(regex);
        if (matcher.find()) {
            String charClass = matcher.group(1);
            if (charClass.length() > 0) {
                // Check if it starts with escaped char
                if (charClass.startsWith("\\") && charClass.length() > 1) {
                    return String.valueOf(charClass.charAt(1));
                } else {
                    // Get first character that's not a backslash or space
                    for (int j = 0; j < charClass.length(); j++) {
                        char c = charClass.charAt(j);
                        if (c != ' ' && c != '\\') {
                            return String.valueOf(c);
                        }
                    }
                }
            }
        }

        // Fallback: Try to find the symbol at the end (before $)
        String pattern = regex;
        if (pattern.endsWith("$")) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        // If the regex allows trailing whitespace (e.g., \s*$), strip that before extracting the symbol
        // so the editor UI can still show the correct trigger text.
        if (pattern.endsWith("\\s*")) {
            pattern = pattern.substring(0, pattern.length() - 3);
        } else if (pattern.endsWith("\\s+")) {
            pattern = pattern.substring(0, pattern.length() - 3);
        }

        // Build the symbol by reading escaped characters from the end
        StringBuilder symbol = new StringBuilder();
        int i = pattern.length() - 1;

        while (i >= 0) {
            char c = pattern.charAt(i);

            // Stop at regex special constructs (but not escaped ones)
            if (c == ')' || c == ']' || c == '+' || c == '*' || c == '?') {
                // Check if this is an escaped character
                if (i > 0 && pattern.charAt(i - 1) == '\\') {
                    // It's escaped, include it in symbol
                    symbol.insert(0, c);
                    i -= 2; // Skip the backslash
                    continue;
                }
                // Not escaped, stop here
                break;
            }

            // Check for escaped character
            if (i > 0 && pattern.charAt(i - 1) == '\\') {
                symbol.insert(0, c);
                i -= 2; // Skip the backslash
            } else if (c == '\\') {
                // Lone backslash, stop
                break;
            } else {
                symbol.insert(0, c);
                i--;
            }

            // Limit symbol length to prevent infinite loops
            if (symbol.length() > 20) {
                break;
            }
        }

        return symbol.length() > 0 ? symbol.toString() : null;
    }

    private static String escapeRegex(String symbol) {
        // Characters that need escaping in regex
        String specialChars = "\\^$.|?*+()[]{}";
        StringBuilder escaped = new StringBuilder();
        for (char c : symbol.toCharArray()) {
            if (specialChars.indexOf(c) >= 0) {
                escaped.append("\\");
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static String escapeLiteralForCharClass(String symbol) {
        // Characters that need escaping inside character class [...]
        String specialChars = "\\^-]";
        StringBuilder escaped = new StringBuilder();
        for (char c : symbol.toCharArray()) {
            if (specialChars.indexOf(c) >= 0) {
                escaped.append("\\");
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}