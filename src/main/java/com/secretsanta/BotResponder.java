package com.secretsanta;

import java.util.logging.Level;
import java.util.logging.Logger;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

final class BotResponder {
    private static final Logger LOGGER = Logger.getLogger(BotResponder.class.getName());

    private final TelegramMessenger telegramMessenger;

    BotResponder(TelegramMessenger telegramMessenger) {
        this.telegramMessenger = telegramMessenger;
    }

    void sendText(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    void sendWithKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        sendMessage(chatId, text, keyboard);
    }

    void answerCallback(String callbackQueryId, String text) {
        try {
            telegramMessenger.answerCallback(callbackQueryId, text);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.TELEGRAM_CALLBACK_ANSWER_FAILED, exception);
        }
    }

    private void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            telegramMessenger.sendMessage(chatId, text, keyboard);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.TELEGRAM_RESPONSE_SEND_FAILED, exception);
        }
    }
}
