package com.secretsanta;

import java.util.ArrayList;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

final class BotButtons {
    static final String ONBOARDING_PREFIX = "onboarding:";
    static final String CLAIM = ONBOARDING_PREFIX + "claim";
    static final String JOIN = ONBOARDING_PREFIX + "join";
    static final String JOIN_CONFIRM = ONBOARDING_PREFIX + "join:confirm";
    static final String JOIN_EDIT = ONBOARDING_PREFIX + "join:edit";
    static final String OPEN = ONBOARDING_PREFIX + "open";
    static final String RESET = ONBOARDING_PREFIX + "reset";
    static final String RESET_CONFIRM_PREFIX = RESET + ":confirm:";
    static final String RESET_CANCEL_PREFIX = RESET + ":cancel:";
    static final String PAIR_PREFIX = "pair:";

    private BotButtons() {}

    static InlineKeyboardMarkup participantStartMenu() {
        return keyboard(List.of(
                List.of(button("Я організатор", CLAIM)),
                List.of(button("Приєднатися за кодом", JOIN))));
    }

    static InlineKeyboardMarkup organizerMenu(boolean registrationOpen) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(button("Відкрити реєстрацію", OPEN)));
        if (registrationOpen) {
            rows.add(List.of(button("Скинути обмін", RESET)));
        }
        return keyboard(rows);
    }

    static InlineKeyboardMarkup joinConfirmation() {
        return keyboard(List.of(
                List.of(button("Підтвердити ім'я", JOIN_CONFIRM)),
                List.of(button("Змінити ім'я", JOIN_EDIT))));
    }

    static InlineKeyboardMarkup resetConfirmation(String token) {
        return keyboard(List.of(
                List.of(button("Так, скинути", RESET_CONFIRM_PREFIX + token)),
                List.of(button("Скасувати", RESET_CANCEL_PREFIX + token))));
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
