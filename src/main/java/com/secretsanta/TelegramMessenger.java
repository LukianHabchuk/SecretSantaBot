package com.secretsanta;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

interface TelegramMessenger {
    void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard)
            throws TelegramApiException;

    void answerCallback(String callbackQueryId, String text) throws TelegramApiException;
}
