package com.papertranslator.settings;

import java.util.Map;

public class SettingsValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public SettingsValidationException(Map<String, String> fieldErrors) {
        super("设置有误：" + String.join("；", fieldErrors.values()));
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
