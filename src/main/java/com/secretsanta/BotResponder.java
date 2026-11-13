package com.secretsanta;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.function.Function;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

final class BotResponder {
    private static final Logger LOGGER = Logger.getLogger(BotResponder.class.getName());

    private final TelegramMessenger telegramMessenger;
    private final LanguageStore languageStore;
    private Long callbackUserId;

    BotResponder(TelegramMessenger telegramMessenger) {
        this(telegramMessenger, null);
    }

    BotResponder(TelegramMessenger telegramMessenger, LanguageStore languageStore) {
        this.telegramMessenger = telegramMessenger;
        this.languageStore = languageStore;
    }

    BotLanguage languageFor(long userId) {
        return languageStore == null || userId <= 0
                ? BotLanguage.UKRAINIAN
                : languageStore.get(userId).orElse(BotLanguage.UKRAINIAN);
    }

    boolean supportsLocalization() {
        return languageStore != null;
    }

    void beginCallback(long userId) {
        callbackUserId = userId;
    }

    void endCallback() {
        callbackUserId = null;
    }

    void sendText(long chatId, String text) {
        sendMessage(chatId, staticText(languageFor(chatId), text), null);
    }

    void sendText(long chatId, Function<BotLanguage, String> text) {
        sendMessage(chatId, text.apply(languageFor(chatId)), null);
    }

    void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        BotLanguage language = languageFor(chatId);
        sendMessage(chatId, staticText(language, text), BotButtons.localize(language, keyboard));
    }

    void sendWithKeyboard(
            long chatId, Function<BotLanguage, String> text, InlineKeyboardMarkup keyboard) {
        BotLanguage language = languageFor(chatId);
        sendMessage(chatId, text.apply(language), BotButtons.localize(language, keyboard));
    }

    void answerCallback(String callbackQueryId, String text) {
        BotLanguage language = callbackUserId == null
                ? BotLanguage.UKRAINIAN : languageFor(callbackUserId);
        sendCallback(callbackQueryId, staticText(language, text));
    }

    void answerCallback(String callbackQueryId, Function<BotLanguage, String> text) {
        BotLanguage language = callbackUserId == null
                ? BotLanguage.UKRAINIAN : languageFor(callbackUserId);
        sendCallback(callbackQueryId, text.apply(language));
    }

    private void sendCallback(String callbackQueryId, String text) {
        try {
            telegramMessenger.answerCallback(callbackQueryId, text);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.TELEGRAM_CALLBACK_ANSWER_FAILED, exception);
        }
    }

    static String staticText(BotLanguage language, String text) {
        if (language != BotLanguage.ENGLISH) {
            return text;
        }
        String translated = EnglishMessages.translate(text);
        if (translated == null) {
            throw new IllegalArgumentException("No English translation for bot message.");
        }
        return translated;
    }

    private void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            telegramMessenger.sendMessage(chatId, text, keyboard);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.TELEGRAM_RESPONSE_SEND_FAILED, exception);
        }
    }
}
