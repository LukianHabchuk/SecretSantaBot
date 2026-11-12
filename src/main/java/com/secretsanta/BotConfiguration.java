package com.secretsanta;

import java.util.Base64;
import java.util.Map;

final class BotConfiguration {
    private final String botToken;
    private final String botUsername;
    private final String setupCode;
    private final byte[] dataKey;

    private BotConfiguration(
            String botToken,
            String botUsername,
            String setupCode,
            byte[] dataKey) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.setupCode = setupCode;
        this.dataKey = dataKey;
    }

    static BotConfiguration from(Map<String, String> environment) {
        String setupCode = required(environment, "SECRET_SANTA_SETUP_CODE");
        if (setupCode.codePointCount(0, setupCode.length()) < 32) {
            throw new IllegalArgumentException(
                    "SECRET_SANTA_SETUP_CODE must contain at least 32 characters.");
        }

        String encodedDataKey = required(environment, "SECRET_SANTA_DATA_KEY");
        byte[] dataKey;
        try {
            dataKey = Base64.getDecoder().decode(encodedDataKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "SECRET_SANTA_DATA_KEY must be valid Base64.", exception);
        }
        if (dataKey.length != 32) {
            throw new IllegalArgumentException(
                    "SECRET_SANTA_DATA_KEY must encode exactly 32 bytes.");
        }

        return new BotConfiguration(
                required(environment, "TELEGRAM_BOT_TOKEN"),
                required(environment, "TELEGRAM_BOT_USERNAME"),
                setupCode,
                dataKey);
    }

    String botToken() {
        return botToken;
    }

    String botUsername() {
        return botUsername;
    }

    String setupCode() {
        return setupCode;
    }

    byte[] dataKey() {
        return dataKey.clone();
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required environment variable " + name + " is missing or blank.");
        }
        return value;
    }
}
