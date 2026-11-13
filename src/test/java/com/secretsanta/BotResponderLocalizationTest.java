package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

class BotResponderLocalizationTest {
    @TempDir
    Path directory;

    @Test
    void localizesPerRecipientIncludingButtonsWithoutTranslatingNames() throws Exception {
        LanguageStore store = new LanguageStore(directory, new byte[32]);
        store.set(101L, BotLanguage.ENGLISH);
        store.set(102L, BotLanguage.UKRAINIAN);
        RecordingMessenger messenger = new RecordingMessenger();
        BotResponder responder = new BotResponder(messenger, store);

        responder.sendWithKeyboard(101L, BotMessages.PARTICIPANT_JOIN_START,
                BotButtons.participantStartMenu(false));
        responder.sendWithKeyboard(102L, BotMessages.PARTICIPANT_JOIN_START,
                BotButtons.participantStartMenu(false));
        assertTrue(messenger.messages.get(0).text().contains("Join"));
        assertEquals("Join with a code", messenger.messages.get(0).keyboard()
                .getKeyboard().getFirst().getFirst().getText());
        assertEquals(BotMessages.PARTICIPANT_JOIN_START, messenger.messages.get(1).text());

        responder.sendWithKeyboard(101L, BotMessages.FIRST_PARTICIPANT_PROMPT,
                BotButtons.pairing(List.of(
                        new SecretSantaBot.Participant(102L, "Скасувати", null)), "pair:first:", null));
        assertEquals("Скасувати", messenger.messages.getLast().keyboard()
                .getKeyboard().getFirst().getFirst().getText());
    }

    @Test
    void localizesCallbacksAndExplicitlyRejectsMissingTranslations() throws Exception {
        LanguageStore store = new LanguageStore(directory, new byte[32]);
        store.set(101L, BotLanguage.ENGLISH);
        RecordingMessenger messenger = new RecordingMessenger();
        BotResponder responder = new BotResponder(messenger, store);

        responder.beginCallback(101L);
        responder.answerCallback("callback", BotMessages.JOIN_CALLBACK);
        responder.endCallback();
        assertEquals("You joined the exchange.", messenger.callback);
        assertThrows(IllegalArgumentException.class,
                () -> responder.sendText(101L, "Untranslated message"));
    }

    private record Message(String text, InlineKeyboardMarkup keyboard) {}

    private static final class RecordingMessenger implements TelegramMessenger {
        private final List<Message> messages = new ArrayList<>();
        private String callback;

        @Override
        public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
            messages.add(new Message(text, keyboard));
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {
            callback = text;
        }

        @Override
        public boolean setMyCommands(SetMyCommands commands) {
            return true;
        }
    }
}
