package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LanguageStoreTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path directory;

    @Test
    void persistsIndependentPreferencesAndDeletesOnlyTheRequestedUser() throws Exception {
        LanguageStore store = new LanguageStore(directory, KEY);
        store.set(101L, BotLanguage.ENGLISH);
        store.set(202L, BotLanguage.UKRAINIAN);

        LanguageStore restarted = new LanguageStore(directory, KEY);
        assertEquals(BotLanguage.ENGLISH, restarted.get(101L).orElseThrow());
        assertEquals(BotLanguage.UKRAINIAN, restarted.get(202L).orElseThrow());

        restarted.delete(101L);
        LanguageStore reloaded = new LanguageStore(directory, KEY);
        assertTrue(reloaded.get(101L).isEmpty());
        assertEquals(BotLanguage.UKRAINIAN, reloaded.get(202L).orElseThrow());
    }

    @Test
    void encryptsPreferencesAndRejectsTamperingAndWrongKeys() throws Exception {
        LanguageStore store = new LanguageStore(directory, KEY);
        store.set(123456789012345L, BotLanguage.ENGLISH);
        byte[] bytes = Files.readAllBytes(directory.resolve("language-preferences.enc"));
        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("123456789012345"));

        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        assertThrows(IOException.class, () -> new LanguageStore(directory, wrongKey));
        bytes[bytes.length - 1] ^= 1;
        Files.write(directory.resolve("language-preferences.enc"), bytes);
        assertThrows(IOException.class, () -> new LanguageStore(directory, KEY));
    }

    @Test
    void failedWriteDoesNotChangeThePreviouslySavedPreference() throws Exception {
        LanguageStore store = new LanguageStore(directory, KEY);
        store.set(101L, BotLanguage.UKRAINIAN);
        LanguageStore failing = new LanguageStore(directory, KEY, (source, target) -> {
            throw new IOException("replacement failed");
        });

        assertThrows(IOException.class, () -> failing.set(101L, BotLanguage.ENGLISH));
        assertEquals(BotLanguage.UKRAINIAN, failing.get(101L).orElseThrow());
        assertEquals(BotLanguage.UKRAINIAN,
                new LanguageStore(directory, KEY).get(101L).orElseThrow());
    }
}
