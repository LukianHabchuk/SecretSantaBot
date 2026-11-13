package com.secretsanta;

import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

final class BotButtons {
    static final String ONBOARDING_PREFIX = "onboarding:";
    static final String CLAIM = ONBOARDING_PREFIX + "claim";
    static final String JOIN = ONBOARDING_PREFIX + "join";
    static final String JOIN_CONFIRM_PREFIX = ONBOARDING_PREFIX + "join:confirm:";
    static final String JOIN_EDIT_PREFIX = ONBOARDING_PREFIX + "join:edit:";
    static final String OPEN = ONBOARDING_PREFIX + "open";
    static final String RESET = ONBOARDING_PREFIX + "reset";
    static final String RESET_CONFIRM_PREFIX = RESET + ":confirm:";
    static final String RESET_CANCEL_PREFIX = RESET + ":cancel:";
    static final String PAIR_PREFIX = "pair:";
    static final String PAIR_REQUEST_PREFIX = "pair-request:";
    static final String PAIR_REQUEST_CHOOSE = PAIR_REQUEST_PREFIX + "choose";
    static final String PAIR_REQUEST_STATUS = PAIR_REQUEST_PREFIX + "status";
    static final String PAIR_REQUEST_TARGET_PREFIX = PAIR_REQUEST_PREFIX + "target:";
    static final String PAIR_REQUEST_ACCEPT_PREFIX = PAIR_REQUEST_PREFIX + "accept:";
    static final String PAIR_REQUEST_DECLINE_PREFIX = PAIR_REQUEST_PREFIX + "decline:";
    static final String PAIR_REQUEST_CANCEL_PREFIX = PAIR_REQUEST_PREFIX + "cancel:";
    static final String ORGANIZER_PAIR = "organizer:pair";
    static final String ORGANIZER_ROSTER = "organizer:roster";
    static final String ORGANIZER_RUN_DRAW = "organizer:run-draw";
    static final String ORGANIZER_DRAW_STATUS = "organizer:draw-status";
    static final String ORGANIZER_REPLACE_DRAW = "organizer:replace-draw";
    static final String REPLACE_DRAW_CONFIRM_PREFIX = ORGANIZER_REPLACE_DRAW + ":confirm:";
    static final String REPLACE_DRAW_CANCEL_PREFIX = ORGANIZER_REPLACE_DRAW + ":cancel:";
    static final String ORGANIZER_CLEANUP = "organizer:cleanup";
    static final String CLEANUP_CONFIRM_PREFIX = ORGANIZER_CLEANUP + ":confirm:";
    static final String CLEANUP_CANCEL_PREFIX = ORGANIZER_CLEANUP + ":cancel:";
    static final String DRAW_RECEIVED_PREFIX = "draw:received:";

    private BotButtons() {}

    static InlineKeyboardMarkup languageSelection() {
        return keyboard(List.of(List.of(
                button("English", "language:en"),
                button("Українська", "language:uk"))));
    }

    static InlineKeyboardMarkup participantStartMenu(boolean organizerAvailable) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (organizerAvailable) {
            rows.add(List.of(button("Я організатор", CLAIM)));
        }
        rows.add(List.of(button("Приєднатися за кодом", JOIN)));
        return keyboard(rows);
    }

    static InlineKeyboardMarkup participantPairMenu() {
        return keyboard(List.of(
                List.of(button("Запросити пару", PAIR_REQUEST_CHOOSE)),
                List.of(button("Мій запит", PAIR_REQUEST_STATUS))));
    }

    static InlineKeyboardMarkup organizerMenu(
            ExchangeState state, boolean registeredParticipant) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (state.phase() == ExchangeState.Phase.EMPTY) {
            rows.add(List.of(button("Відкрити реєстрацію", OPEN)));
        }
        if (state.phase() == ExchangeState.Phase.REGISTRATION_OPEN) {
            rows.add(List.of(button("Скинути обмін", RESET)));
            rows.add(List.of(button("Керувати парами", ORGANIZER_PAIR)));
            rows.add(List.of(button("Список учасників", ORGANIZER_ROSTER)));
            rows.add(List.of(button("Провести жеребкування", ORGANIZER_RUN_DRAW)));
            if (registeredParticipant) {
                rows.addAll(participantPairMenu().getKeyboard());
            }
        } else if (state.phase() == ExchangeState.Phase.DELIVERING) {
            rows.add(List.of(button("Повторити доставку", ORGANIZER_RUN_DRAW)));
            rows.add(List.of(button("Статус обміну", ORGANIZER_DRAW_STATUS)));
        } else if (state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS) {
            rows.add(List.of(button("Статус обміну", ORGANIZER_DRAW_STATUS)));
            rows.add(List.of(button("Провести нове жеребкування", ORGANIZER_REPLACE_DRAW)));
            if (state.participants().stream()
                    .allMatch(participant ->
                            state.confirmedUserIds().contains(participant.userId()))) {
                rows.add(List.of(button("Очистити обмін", ORGANIZER_CLEANUP)));
            }
        }
        return keyboard(rows);
    }

    static InlineKeyboardMarkup joinConfirmation(String token) {
        return keyboard(List.of(
                List.of(button("Підтвердити ім'я", JOIN_CONFIRM_PREFIX + token)),
                List.of(button("Змінити ім'я", JOIN_EDIT_PREFIX + token))));
    }

    static InlineKeyboardMarkup resetConfirmation(String token) {
        return keyboard(List.of(
                List.of(button("Так, скинути", RESET_CONFIRM_PREFIX + token)),
                List.of(button("Скасувати", RESET_CANCEL_PREFIX + token))));
    }

    static InlineKeyboardMarkup cleanupConfirmation(String token) {
        return keyboard(List.of(
                List.of(button("Так, видалити обмін", CLEANUP_CONFIRM_PREFIX + token)),
                List.of(button("Скасувати", CLEANUP_CANCEL_PREFIX + token))));
    }

    static InlineKeyboardMarkup replacementDrawConfirmation(String token) {
        return keyboard(List.of(
                List.of(button("Так, замінити розподіл", REPLACE_DRAW_CONFIRM_PREFIX + token)),
                List.of(button("Скасувати", REPLACE_DRAW_CANCEL_PREFIX + token))));
    }

    static InlineKeyboardMarkup assignmentReceived(long drawGeneration) {
        return keyboard(List.of(List.of(
                button("Отримав подарункове завдання", DRAW_RECEIVED_PREFIX + drawGeneration))));
    }

    static InlineKeyboardMarkup pairRequestResponse(String acceptToken, String declineToken) {
        return keyboard(List.of(
                List.of(button("Підтвердити пару", PAIR_REQUEST_ACCEPT_PREFIX + acceptToken)),
                List.of(button("Відхилити", PAIR_REQUEST_DECLINE_PREFIX + declineToken))));
    }

    static InlineKeyboardMarkup pairRequestCancellation(String token) {
        return keyboard(List.of(List.of(
                button("Скасувати запит", PAIR_REQUEST_CANCEL_PREFIX + token))));
    }

    static InlineKeyboardMarkup pairing(
            List<SecretSantaBot.Participant> participants,
            String callbackPrefix,
            Long excludedParticipantId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (SecretSantaBot.Participant participant : participants) {
            if (excludedParticipantId != null
                    && participant.chatId() == excludedParticipantId) {
                continue;
            }
            rows.add(List.of(button(
                    participant.name(),
                    callbackPrefix + participant.chatId())));
        }
        return keyboard(rows);
    }

    static InlineKeyboardMarkup keyboard(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }
}
