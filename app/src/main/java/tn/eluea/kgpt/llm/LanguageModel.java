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
package tn.eluea.kgpt.llm;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public enum LanguageModel {
    Gemini("Gemini", "gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta", true,
            "https://aistudio.google.com/app/apikey"),
    ChatGPT("ChatGPT", "gpt-4o", "https://api.openai.com/v1", false, "https://platform.openai.com/api-keys"),
    Groq("Groq", "llama-3.3-70b-versatile", "https://api.groq.com/openai/v1", true, "https://console.groq.com/keys"),
    OpenRouter("OpenRouter", "google/gemini-2.0-flash-exp:free", "https://openrouter.ai/api/v1", true,
            "https://openrouter.ai/keys"),
    Claude("Claude", "claude-sonnet-4-5-20250630", "https://api.anthropic.com/v1", false,
            "https://console.anthropic.com/settings/keys"),
    Mistral("Mistral", "mistral-small-latest", "https://api.mistral.ai/v1", false,
            "https://console.mistral.ai/api-keys"),
    Chutes("Chutes", "deepseek-ai/DeepSeek-R1-Distill-Llama-70B", "https://api.chutes.ai/v1", false,
                "https://chutes.ai"),
    Perplexity("Perplexity", "sonar-pro", "https://api.perplexity.ai", false,
                "https://www.perplexity.ai/settings/api"),
    GLM("ZhipuAI", "glm-4", "https://open.bigmodel.cn/api/paas/v4", false,
                "https://open.bigmodel.cn/usercenter/apikeys"),
            ;

    public final String label;
    public final boolean isFree;
    public final String getKeyUrl;

    public final Map<LanguageModelField, String> defaults;

    LanguageModel(String label, String defaultSubModel, String defaultBaseUrl, boolean isFree, String getKeyUrl) {
        this.label = label;
        this.isFree = isFree;
        this.getKeyUrl = getKeyUrl;

        defaults = ImmutableMap.of(
                LanguageModelField.SubModel, defaultSubModel,
                LanguageModelField.BaseUrl, defaultBaseUrl,
                LanguageModelField.MaxTokens, "4096",
                LanguageModelField.Temperature, "1.0",
                LanguageModelField.TopP, "1.0");
    }

    public String getDefault(LanguageModelField field) {
        return defaults.getOrDefault(field, null);
    }
}
