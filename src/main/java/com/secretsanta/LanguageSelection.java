package com.secretsanta;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

final class LanguageSelection {
    private static final Logger LOGGER = Logger.getLogger(LanguageSelection.class.getName());
    private final LanguageStore store;
    private final TelegramMessenger messenger;
    private final Set<Long> awaitingChoice = new HashSet<>();

    LanguageSelection(LanguageStore store, TelegramMessenger messenger) {
        this.store = store;
        this.messenger = messenger;
    }

    boolean requiresSelection(long userId) {
        return store.get(userId).isEmpty();
    }

    void prompt(long userId) {
        try {
            messenger.sendMessage(
                    userId,
                    "Choose your language / Оберіть мову:",
                    BotButtons.languageSelection());
            awaitingChoice.add(userId);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.WARNING, "Could not send language selection.", exception);
        }
    }

    boolean choose(long userId, long chatId, String data, String callbackId) {
        BotLanguage language = BotButtons.LANGUAGE_ENGLISH.equals(data) ? BotLanguage.ENGLISH
                : BotButtons.LANGUAGE_UKRAINIAN.equals(data) ? BotLanguage.UKRAINIAN : null;
        if (chatId != userId || language == null || !awaitingChoice.contains(userId)) {
            answer(callbackId, "This selection is unavailable / Вибір недоступний.");
            return false;
        }
        try {
            store.set(userId, language);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not save language preference.", exception);
            sendError(userId);
            answer(callbackId, "Could not save / Не вдалося зберегти.");
            return false;
        }
        awaitingChoice.remove(userId);
        answer(callbackId, language == BotLanguage.ENGLISH ? "Language saved." : "Мову збережено.");
        return true;
    }

    boolean forget(long userId) {
        try {
            store.delete(userId);
            awaitingChoice.remove(userId);
            return true;
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not delete language preference.", exception);
            sendError(userId);
            return false;
        }
    }

    private void sendError(long userId) {
        try {
            messenger.sendMessage(userId,
                    "The language preference could not be saved or deleted. Please try again. "
                            + "Не вдалося зберегти або видалити вибір мови. Спробуйте ще раз.",
                    null);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.WARNING, "Could not report language preference error.", exception);
        }
    }

    private void answer(String callbackId, String text) {
        try {
            messenger.answerCallback(callbackId, text);
        } catch (TelegramApiException exception) {
            LOGGER.log(Level.WARNING, "Could not answer language selection.", exception);
        }
    }
}
