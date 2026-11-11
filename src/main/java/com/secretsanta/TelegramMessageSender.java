package com.secretsanta;

import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@FunctionalInterface
interface TelegramMessageSender {
    void send(long chatId, String text) throws TelegramApiException;
}
