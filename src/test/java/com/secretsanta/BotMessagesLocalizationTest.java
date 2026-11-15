package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BotMessagesLocalizationTest {
    @Test
    void localizedMessagesPreserveNamesAndCodes() {
        assertEquals("Join the exchange as **Олена**?",
                BotMessages.joinConfirmation(BotLanguage.ENGLISH, "Олена"));
        assertTrue(BotMessages.participantJoined(BotLanguage.ENGLISH, "Олена").contains("Олена"));
        assertTrue(BotMessages.registrationOpened(BotLanguage.ENGLISH, "abc-123").contains("`abc-123`"));
        assertEquals(BotMessages.joinConfirmation("Олена"),
                BotMessages.joinConfirmation(BotLanguage.UKRAINIAN, "Олена"));
    }

    @Test
    void englishPairNotificationsUseTheCorrectNamesAndDirection() {
        assertTrue(BotMessages.pairRequestReceived(BotLanguage.ENGLISH, "Олена")
                .contains("Олена"));
        assertTrue(BotMessages.pairRequestStatus(BotLanguage.ENGLISH, "Олена", true)
                .contains("awaiting"));
        assertTrue(BotMessages.pairRequestStatus(BotLanguage.ENGLISH, "Олена", false)
                .contains("your response"));
        assertTrue(BotMessages.pairChanged(BotLanguage.ENGLISH, "Олена").contains("Олена"));
    }

    @Test
    void rosterAndDrawStatusAreLocalizedWithoutChangingParticipantNames() {
        List<SecretSantaBot.Participant> roster = List.of(
                new SecretSantaBot.Participant(101L, "Олена", null),
                new SecretSantaBot.Participant(102L, "Sam", null));
        String text = BotMessages.participantRoster(BotLanguage.ENGLISH, roster,
                List.of(new ExchangeState.PairRequest(101L, 102L)));
        assertTrue(text.contains("Participants"));
        assertTrue(text.contains("request to `Sam`"));
        assertTrue(text.contains("request from `Олена`"));

        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS, null,
                List.of(new ExchangeState.Participant(101L, "Олена", null),
                        new ExchangeState.Participant(102L, "Sam", null)),
                List.of(), 1,
                List.of(new ExchangeState.Assignment(101L, 102L),
                        new ExchangeState.Assignment(102L, 101L)),
                Set.of(101L, 102L), Set.of(101L));
        String status = BotMessages.drawStatus(BotLanguage.ENGLISH, state);
        assertTrue(status.contains("Delivered: 2/2"));
        assertTrue(status.contains("Олена — confirmed receipt"));
        assertTrue(status.contains("Sam — awaiting confirmation"));
    }

    @Test
    void lateRegistrationControlsArePhaseGatedAndLocalized() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Олена", null),
                new ExchangeState.Participant(102L, "Sam", null));
        List<ExchangeState.Assignment> assignments = List.of(
                new ExchangeState.Assignment(101L, 102L),
                new ExchangeState.Assignment(102L, 101L));
        ExchangeState delivered = new ExchangeState(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                null,
                participants,
                List.of(),
                1,
                assignments,
                Set.of(101L, 102L),
                Set.of());

        var deliveredMenu = BotButtons.organizerMenu(delivered, false);
        assertTrue(deliveredMenu.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> BotButtons.ORGANIZER_OPEN_LATE_REGISTRATION
                        .equals(button.getCallbackData())));
        assertTrue(BotButtons.localize(BotLanguage.ENGLISH, deliveredMenu).getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().equals("Open late registration")));

        ExchangeState lateRegistration = new ExchangeState(
                ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                "invite-code",
                participants,
                List.of(),
                1,
                assignments,
                Set.of(101L, 102L),
                Set.of());
        var lateMenu = BotButtons.organizerMenu(lateRegistration, false);
        assertFalse(lateMenu.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> BotButtons.ORGANIZER_OPEN_LATE_REGISTRATION
                        .equals(button.getCallbackData())));
        assertTrue(lateMenu.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> BotButtons.ORGANIZER_REPLACE_DRAW
                        .equals(button.getCallbackData())));
        assertTrue(EnglishMessages.translate(BotMessages.LATE_REGISTRATION_UNAVAILABLE)
                .contains("fully delivered"));
    }
}
