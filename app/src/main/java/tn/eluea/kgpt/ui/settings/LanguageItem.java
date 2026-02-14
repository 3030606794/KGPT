/*
 * Copyright (c) 2025 Amr Aldeeb @Eluea
 * GitHub: https://github.com/Eluea
 * Telegram: https://t.me/Eluea
 *
 * Licensed under the GPLv3.
 */
package tn.eluea.kgpt.ui.settings;

public class LanguageItem {
    private String code;
    private String name;
    private String nativeName;
    private boolean isSelected;

    public LanguageItem(String code, String name, String nativeName, boolean isSelected) {
        this.code = code;
        this.name = name;
        this.nativeName = nativeName;
        this.isSelected = isSelected;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getNativeName() {
        return nativeName;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }
}
