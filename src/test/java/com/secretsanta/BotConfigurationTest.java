package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BotConfigurationTest {
    private static final String ENCODED_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);
    private static final String SETUP_CODE = "s".repeat(32);

    @Test
    void loadsRequiredSettingsFromEnvironment() {
        BotConfiguration configuration = BotConfiguration.from(Map.of(
                "TELEGRAM_BOT_TOKEN", "test-token",
                "TELEGRAM_BOT_USERNAME", "test-bot",
                "SECRET_SANTA_SETUP_CODE", SETUP_CODE,
                "SECRET_SANTA_DATA_KEY", ENCODED_KEY));

        assertEquals("test-token", configuration.botToken());
        assertEquals("test-bot", configuration.botUsername());
        assertEquals(SETUP_CODE, configuration.setupCode());
        assertArrayEquals(new byte[32], configuration.dataKey());
    }

    @Test
    void rejectsSetupCodesShorterThan32CharactersWithoutEchoingTheValue() {
        String shortCode = "s".repeat(31);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BotConfiguration.from(Map.of(
                        "TELEGRAM_BOT_TOKEN", "test-token",
                        "TELEGRAM_BOT_USERNAME", "test-bot",
                        "SECRET_SANTA_SETUP_CODE", shortCode,
                        "SECRET_SANTA_DATA_KEY", ENCODED_KEY)));

        assertTrue(exception.getMessage().contains("32 characters"));
        assertFalse(exception.getMessage().contains(shortCode));
    }

    @Test
    void rejectsMissingRequiredSettingsWithoutIncludingSecretValues() {
        Map<String, String> environment = Map.of(
                "TELEGRAM_BOT_TOKEN", "test-token",
                "TELEGRAM_BOT_USERNAME", "test-bot",
                "SECRET_SANTA_SETUP_CODE", SETUP_CODE,
                "SECRET_SANTA_DATA_KEY", ENCODED_KEY);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BotConfiguration.from(Map.of(
                        "TELEGRAM_BOT_USERNAME", environment.get("TELEGRAM_BOT_USERNAME"),
                        "SECRET_SANTA_SETUP_CODE", environment.get("SECRET_SANTA_SETUP_CODE"),
                        "SECRET_SANTA_DATA_KEY", environment.get("SECRET_SANTA_DATA_KEY"))));

        assertTrue(exception.getMessage().contains("TELEGRAM_BOT_TOKEN"));
        assertFalse(exception.getMessage().contains("test-token"));
    }

    @Test
    void rejectsBlankRequiredSettings() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BotConfiguration.from(Map.of(
                        "TELEGRAM_BOT_TOKEN", "test-token",
                        "TELEGRAM_BOT_USERNAME", " ",
                        "SECRET_SANTA_SETUP_CODE", SETUP_CODE,
                        "SECRET_SANTA_DATA_KEY", ENCODED_KEY)));

        assertTrue(exception.getMessage().contains("TELEGRAM_BOT_USERNAME"));
    }

    @Test
    void rejectsMalformedDataKeyWithoutEchoingTheValue() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BotConfiguration.from(Map.of(
                        "TELEGRAM_BOT_TOKEN", "test-token",
                        "TELEGRAM_BOT_USERNAME", "test-bot",
                        "SECRET_SANTA_SETUP_CODE", SETUP_CODE,
                        "SECRET_SANTA_DATA_KEY", "not-base64")));

        assertTrue(exception.getMessage().contains("SECRET_SANTA_DATA_KEY"));
        assertFalse(exception.getMessage().contains("not-base64"));
    }

    @Test
    void rejectsDataKeysThatAreNot256Bits() {
        String encodedKey = Base64.getEncoder().encodeToString(new byte[31]);
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> BotConfiguration.from(Map.of(
                        "TELEGRAM_BOT_TOKEN", "test-token",
                        "TELEGRAM_BOT_USERNAME", "test-bot",
                        "SECRET_SANTA_SETUP_CODE", SETUP_CODE,
                        "SECRET_SANTA_DATA_KEY", encodedKey)));

        assertTrue(exception.getMessage().contains("32 bytes"));
    }
}
