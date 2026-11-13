package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

class LanguageSelectionTest {
    @TempDir
    Path directory;

    @Test
    void selectionIsPrivateAndPersistsAcrossRestarts() throws Exception {
        LanguageStore store = new LanguageStore(directory, new byte[32]);
        RecordingMessenger messenger = new RecordingMessenger();
        LanguageSelection selection = new LanguageSelection(store, messenger);

        assertTrue(selection.requiresSelection(101));
        selection.prompt(101);
        assertTrue(messenger.keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().equals("English")));
        assertFalse(selection.choose(101, 202, "language:en", "wrong-chat"));
        assertTrue(store.get(101).isEmpty());

        assertTrue(selection.choose(101, 101, "language:en", "selected"));
        assertEquals(BotLanguage.ENGLISH,
                new LanguageStore(directory, new byte[32]).get(101).orElseThrow());
        assertFalse(selection.requiresSelection(101));
    }

    @Test
    void rejectsUnknownLanguageAndAllowsAUserToForgetTheirChoice() throws Exception {
        LanguageStore store = new LanguageStore(directory, new byte[32]);
        RecordingMessenger messenger = new RecordingMessenger();
        LanguageSelection selection = new LanguageSelection(store, messenger);
        selection.prompt(101);
        assertFalse(selection.choose(101, 101, "language:fr", "unknown"));
        assertFalse(selection.choose(101, 101, null, "missing"));
        assertTrue(store.get(101).isEmpty());
        assertTrue(selection.choose(101, 101, "language:uk", "selected"));

        assertTrue(selection.forget(101));
        assertTrue(new LanguageStore(directory, new byte[32]).get(101).isEmpty());
    }

    @Test
    void reportsSaveFailuresAndDoesNotClaimSuccess() throws Exception {
        LanguageStore store = new LanguageStore(directory, new byte[32], (source, target) -> {
            throw new java.io.IOException("write failed");
        });
        RecordingMessenger messenger = new RecordingMessenger();
        LanguageSelection selection = new LanguageSelection(store, messenger);
        selection.prompt(101);

        assertFalse(selection.choose(101, 101, "language:en", "failed"));
        assertTrue(store.get(101).isEmpty());
        assertTrue(messenger.messages.getLast().contains("could not"));
    }

    private static final class RecordingMessenger implements TelegramMessenger {
        private final List<String> messages = new ArrayList<>();
        private InlineKeyboardMarkup keyboard;

        @Override
        public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
            messages.add(text);
            this.keyboard = keyboard;
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {}

        @Override
        public boolean setMyCommands(SetMyCommands commands) {
            return true;
        }
    }
}
