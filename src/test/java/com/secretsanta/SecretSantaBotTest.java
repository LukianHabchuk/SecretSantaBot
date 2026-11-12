package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

class SecretSantaBotTest {
    private static final byte[] TEST_DATA_KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void participantRetainsRegistrationDetails() {
        SecretSantaBot.Participant participant =
                new SecretSantaBot.Participant(123L, "Alex", 456L);

        assertEquals(123L, participant.chatId());
        assertEquals("Alex", participant.name());
        assertEquals(456L, participant.partnerId());
    }

    @Test
    void startShowsPrivateOnboardingButtonsInsteadOfCommandInstructions() {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);

        bot.onUpdateReceived(update(123L, 123L, "private", "/start"));

        InlineKeyboardMarkup keyboard = messenger.sentMessages.getLast().keyboard();
        assertTrue(keyboard != null);
        assertTrue(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getCallbackData().equals("onboarding:claim")));
        assertTrue(keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getCallbackData().equals("onboarding:join")));
        assertFalse(messenger.sentMessages.getLast().text().contains("/claim <код>"));
    }

    @Test
    void startInGroupDoesNotOfferOnboardingButtons() {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);

        bot.onUpdateReceived(update(-900L, 123L, "group", "/start"));

        assertTrue(messenger.sentMessages.getLast().keyboard() == null);
        assertTrue(messenger.sentMessages.getLast().text().contains("особистому чаті"));
    }

    @Test
    void organizerCanClaimAndOpenRegistrationUsingButtons() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:claim", 100L, 100L, "claim", "Organizer"));
        bot.onUpdateReceived(update(100L, 100L, "private", "setup-code"));
        assertTrue(organizerService.isOrganizer(100L));

        String openCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Відкрити реєстрацію");
        bot.onUpdateReceived(callbackUpdate(openCallback, 100L, 100L, "open", "Organizer"));

        assertTrue(messenger.sentMessages.getLast().text().contains("Запрошувальний код:"));
        assertEquals(100L, messenger.sentMessages.getLast().chatId());
    }

    @Test
    void incorrectSetupCodeDoesNotClaimOrganizerThroughButtonFlow() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:claim", 100L, 100L, "claim", "Organizer"));
        bot.onUpdateReceived(update(100L, 100L, "private", "wrong-code"));

        assertFalse(organizerService.isOrganizer(100L));
        assertTrue(messenger.sentMessages.getLast().text().contains("Не вдалося підтвердити"));
        assertFalse(messenger.sentMessages.getLast().text().contains("wrong-code"));
    }

    @Test
    void correctSetupCodeCannotClaimOrganizerAfterAnotherUserHasClaimed() throws Exception {
        OrganizerService organizerService = createService();
        organizerService.claim(100L, "setup-code");
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:claim", 201L, 201L, "claim", "Guest"));
        bot.onUpdateReceived(update(201L, 201L, "private", "setup-code"));

        assertTrue(organizerService.isOrganizer(100L));
        assertFalse(organizerService.isOrganizer(201L));
        assertFalse(messenger.sentMessages.getLast().text().contains("підтверджено"));
    }

    @Test
    void participantJoinsWithConfirmedTelegramNameUsingButtons() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String confirmCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(callbackUpdate(confirmCallback, 201L, 201L, "confirm", "Alex"));

        ExchangeState exchangeState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertTrue(exchangeState.participants().stream()
                .anyMatch(participant -> participant.userId() == 201L
                        && participant.name().equals("Alex")));
        assertTrue(messenger.sentMessages.getLast().text().contains("приєднався"));
    }

    @Test
    void participantCanEditSuggestedNameBeforeJoining() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String editCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити ім'я");
        bot.onUpdateReceived(callbackUpdate(editCallback, 201L, 201L, "edit", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "Alex Example", "Alex"));
        String confirmCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(callbackUpdate(
                confirmCallback, 201L, 201L, "confirm", "Alex"));

        ExchangeState exchangeState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertTrue(exchangeState.participants().stream()
                .anyMatch(participant -> participant.userId() == 201L
                        && participant.name().equals("Alex Example")));
    }

    @Test
    void staleJoinConfirmationCannotConfirmAnEditedName() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String oldConfirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");

        String editCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити ім'я");
        bot.onUpdateReceived(callbackUpdate(editCallback, 201L, 201L, "edit", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "Alex Example", "Alex"));
        bot.onUpdateReceived(callbackUpdate(
                oldConfirmation, 201L, 201L, "stale-confirm", "Alex"));

        ExchangeState exchangeState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertTrue(exchangeState.participants().stream()
                .noneMatch(participant -> participant.userId() == 201L));
        assertTrue(messenger.callbackAnswers.getLast().text().contains("застарів"));
    }

    @Test
    void participantCannotJoinTwiceOrReplaceTheirConfirmedName() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join-1", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String firstConfirm = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(callbackUpdate(firstConfirm, 201L, 201L, "confirm-1", "Alex"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join-2", "Different"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Different"));
        String secondConfirm = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(callbackUpdate(secondConfirm, 201L, 201L, "confirm-2", "Different"));

        ExchangeState exchangeState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(
                "Alex",
                exchangeState.participants().stream()
                        .filter(participant -> participant.userId() == 201L)
                        .findFirst()
                        .orElseThrow()
                        .name());
        assertTrue(messenger.sentMessages.getLast().text().contains("вже зареєстрований"));
    }

    @Test
    void invalidInviteDoesNotRegisterAndPromptsForAnotherCode() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "invalid-code"));

        assertTrue(messenger.sentMessages.getLast().text().contains("закрита"));
        assertTrue(messenger.sentMessages.getLast().keyboard() == null);
        assertTrue(new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().isEmpty());
    }

    @Test
    void staleJoinConfirmationCannotRegisterAfterTheDrawStarts() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String staleConfirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(update(
                202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));
        bot.onUpdateReceived(callbackUpdate(
                staleConfirmation, 201L, 201L, "stale-confirm", "Alex"));

        ExchangeState exchangeState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertTrue(exchangeState.participants().stream()
                .noneMatch(participant -> participant.userId() == 201L));
        assertTrue(messenger.sentMessages.getLast().text().contains("закрита"));
    }

    @Test
    void organizerCanResetOnlyAfterExplicitConfirmation() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(callbackUpdate(
                "onboarding:reset", 100L, 100L, "reset", "Organizer"));
        assertEquals(
                ExchangeState.Phase.REGISTRATION_OPEN,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .phase());
        String confirmCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Так, скинути");
        bot.onUpdateReceived(callbackUpdate(
                confirmCallback, 100L, 100L, "confirm-reset", "Organizer"));

        assertEquals(
                ExchangeState.Phase.EMPTY,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .phase());
        assertTrue(organizerService.isOrganizer(100L));
        assertTrue(messenger.sentMessages.getLast().keyboard() != null);
    }

    @Test
    void nonOrganizerCannotResetUsingForgedButtonCallback() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        openRegistrationWithButtons(bot, messenger);

        bot.onUpdateReceived(callbackUpdate(
                "onboarding:reset", 201L, 201L, "forged-reset", "Guest"));

        assertEquals(
                ExchangeState.Phase.REGISTRATION_OPEN,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .phase());
        assertTrue(messenger.callbackAnswers.getLast().text().contains("лише організатору"));
    }

    @Test
    void nonOrganizerCannotOpenRegistrationUsingForgedButtonCallback() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(callbackUpdate(
                "onboarding:open", 201L, 201L, "forged-open", "Guest"));

        assertFalse(organizerService.isOrganizer(201L));
        assertTrue(new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().isEmpty());
        assertTrue(messenger.callbackAnswers.getLast().text().contains("лише організатору"));
    }

    @Test
    void rejectsSetupCodeClaimsFromGroupChats() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(900L, 123L, "group", "/claim setup-code"));

        assertFalse(organizerService.isOrganizer(123L));
        assertTrue(sentMessages.getFirst().text().contains("особистому чаті"));
    }

    @Test
    void invalidSetupCodeDoesNotClaimOrganizer() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 123L, "private", "/claim wrong-code"));

        assertFalse(organizerService.isOrganizer(123L));
        assertTrue(sentMessages.getFirst().text().contains("Не вдалося отримати права"));
        assertFalse(sentMessages.getFirst().text().contains("wrong-code"));
    }

    @Test
    void persistsTelegramUserIdRatherThanChatId() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 456L, "private", "/claim setup-code"));

        assertTrue(organizerService.isOrganizer(456L));
        assertFalse(organizerService.isOrganizer(123L));
    }

    @Test
    void organizerCanOpenRegistrationAndReceivesInviteCodePrivately() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 123L, "private", "/claim setup-code"));
        bot.onUpdateReceived(update(123L, 123L, "private", "/open"));

        SentMessage inviteMessage = sentMessages.getLast();
        assertTrue(organizerService.isRegistrationOpen());
        assertEquals(123L, inviteMessage.chatId());
        assertTrue(inviteMessage.text().matches(
                "(?s).*Запрошувальний код: `[A-Za-z0-9_-]{32}`.*"));
        bot.onUpdateReceived(update(123L, 123L, "private", "/list"));
        assertTrue(sentMessages.getLast().text().contains("User 123"));
    }

    @Test
    void organizerCannotOpenRegistrationFromAGroupChat() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 123L, "private", "/claim setup-code"));
        bot.onUpdateReceived(update(-900L, 123L, "group", "/open"));

        assertFalse(organizerService.isRegistrationOpen());
        assertFalse(sentMessages.getLast().text().contains("Запрошувальний код:"));
    }

    @Test
    void unclaimedUserCannotOpenRegistration() throws Exception {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 123L, "private", "/open"));

        assertFalse(organizerService.isRegistrationOpen());
        assertFalse(sentMessages.getFirst().text().contains("Запрошувальний код:"));
    }

    @Test
    void nonOrganizerCannotInspectTheRoster() {
        OrganizerService organizerService = createService();
        List<SentMessage> sentMessages = new ArrayList<>();
        SecretSantaBot bot = createBot(organizerService, sentMessages);

        bot.onUpdateReceived(update(123L, 123L, "private", "/list"));

        assertFalse(sentMessages.getFirst().text().contains("Список учасників"));
        assertTrue(sentMessages.getFirst().text().contains("лише організатору"));
    }

    @Test
    void participantCanJoinOnlyWithTheCurrentInviteAndCannotDuplicateRegistration()
            throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        openRegistration(bot, messenger);
        String inviteCode = inviteCode(messenger.sentMessages.getLast().text());
        bot.onUpdateReceived(update(
                201L, 201L, "private", "/join " + inviteCode + " Alex_*[Example]"));
        bot.onUpdateReceived(update(
                201L, 201L, "private", "/join " + inviteCode + " Different Name"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));

        assertTrue(messenger.sentMessages.get(2).text().contains("приєднався"));
        assertTrue(messenger.sentMessages.get(3).text().contains("вже зареєстрований"));
        String roster = messenger.sentMessages.getLast().text();
        assertTrue(roster.contains("Alex\\_\\*\\[Example]"));
        assertFalse(roster.contains("Different Name"));
    }

    @Test
    void invalidInviteDoesNotRegisterParticipant() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join invalid-code Alex"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));

        assertTrue(messenger.sentMessages.get(2).text().contains("недійсний"));
        assertTrue(messenger.sentMessages.getLast().text().contains("User 100"));
        assertFalse(messenger.sentMessages.getLast().text().contains("Alex"));
    }

    @Test
    void participantCannotJoinFromAGroupChat() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(-900L, 201L, "group", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));

        assertTrue(messenger.sentMessages.get(2).text().contains("лише в особистому чаті"));
        assertTrue(messenger.sentMessages.getLast().text().contains("User 100"));
        assertFalse(messenger.sentMessages.getLast().text().contains("Alex"));
    }

    @Test
    void inlinePairingIsOrganizerOnlyAndReportsReplacedPairs() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 204L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }

        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        int sentCount = messenger.sentMessages.size();
        String firstChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 201");
        bot.onUpdateReceived(callbackUpdate(firstChoice, 999L, 999L, "unauthorized"));
        assertEquals(sentCount, messenger.sentMessages.size());
        assertTrue(messenger.callbackAnswers.getLast().text().contains("лише організатору"));

        bot.onUpdateReceived(callbackUpdate(firstChoice, 100L, 100L, "first-201"));
        String oldSecondChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 202");
        assertFalse(messenger.sentMessages.getLast().keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().equals("Guest 201")));

        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        String newFirstChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 203");
        bot.onUpdateReceived(callbackUpdate(newFirstChoice, 100L, 100L, "first-203"));
        String currentSecondChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 204");
        bot.onUpdateReceived(callbackUpdate(oldSecondChoice, 100L, 100L, "stale-second"));
        assertTrue(messenger.callbackAnswers.getLast().text().contains("Список змінився"));
        bot.onUpdateReceived(callbackUpdate(currentSecondChoice, 100L, 100L, "second-204"));

        pairParticipants(bot, messenger, "Guest 201", "Guest 202");
        pairParticipants(bot, messenger, "Guest 203", "Guest 204");
        pairParticipants(bot, messenger, "Guest 201", "Guest 203");

        String replacement = messenger.sentMessages.getLast().text();
        assertTrue(replacement.contains("Попередні пари видалено"));
        assertTrue(replacement.contains("Guest 201 + Guest 202"));
        assertTrue(replacement.contains("Guest 203 + Guest 204"));
    }

    @Test
    void inlinePairingRejectsStaleSecondChoiceFromEarlierFlow() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }

        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        String oldFirstChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 201");
        bot.onUpdateReceived(callbackUpdate(oldFirstChoice, 100L, 100L, "old-first"));
        String staleSecondChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 202");

        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        String currentFirstChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Guest 201");
        bot.onUpdateReceived(callbackUpdate(currentFirstChoice, 100L, 100L, "new-first"));
        callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Guest 203");

        bot.onUpdateReceived(callbackUpdate(staleSecondChoice, 100L, 100L, "stale-second"));

        assertTrue(messenger.callbackAnswers.getLast().text().contains("Список змінився"));
        assertTrue(messenger.sentMessages.getLast().keyboard() != null);
    }

    @Test
    void inlinePairingDoesNotExposeButtonsInGroupChats() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));
        bot.onUpdateReceived(update(-900L, 100L, "group", "/pair"));

        assertTrue(messenger.sentMessages.getLast().keyboard() == null);
        assertTrue(messenger.sentMessages.getLast().text().contains("особистому чаті"));
    }

    @Test
    void participantsCanRequestAndAcceptPairingInPrivateChats() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        String requestButton = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Запросити пару");
        bot.onUpdateReceived(callbackUpdate(requestButton, 201L, 201L, "choose"));
        String candidate = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Sam");
        bot.onUpdateReceived(callbackUpdate(candidate, 201L, 201L, "request"));

        ExchangeService exchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(
                List.of(new ExchangeState.PairRequest(201L, 202L)),
                exchangeService.state().pairRequests());
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));
        assertTrue(messenger.sentMessages.getLast().text().contains("запит до"));
        assertTrue(messenger.sentMessages.getLast().text().contains("запит від"));
        SentMessage recipientNotice = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 202L)
                .reduce((first, second) -> second)
                .orElseThrow();
        SentMessage requesterNotice = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 201L)
                .reduce((first, second) -> second)
                .orElseThrow();
        String accept = callbackDataFor(recipientNotice.keyboard(), "Підтвердити пару");
        String cancel = callbackDataFor(requesterNotice.keyboard(), "Скасувати запит");

        int sentCount = messenger.sentMessages.size();
        bot.onUpdateReceived(callbackUpdate(accept, 203L, 203L, "unauthorized"));
        assertEquals(sentCount, messenger.sentMessages.size());
        assertTrue(exchangeService.state().pairRequests().size() == 1);

        bot.onUpdateReceived(callbackUpdate(accept, 202L, 202L, "accept"));

        ExchangeService acceptedExchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(202L, acceptedExchangeService.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, acceptedExchangeService.participant(202L).orElseThrow().partnerId());
        assertTrue(acceptedExchangeService.state().pairRequests().isEmpty());
        assertTrue(messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 201L || message.chatId() == 202L)
                .anyMatch(message -> message.text().contains("Пару підтверджено")));
        assertTrue(cancel.startsWith("pair-request:cancel:"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Мій запит"),
                201L,
                201L,
                "status"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Твоя пара"));
    }

    @Test
    void stalePairRequestButtonsCannotActOnAReplacementRequest() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Запросити пару"),
                201L,
                201L,
                "choose-1"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Sam"),
                201L,
                201L,
                "request-1"));
        SentMessage firstRecipientNotice = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 202L)
                .reduce((first, second) -> second)
                .orElseThrow();
        String staleAccept = callbackDataFor(firstRecipientNotice.keyboard(), "Підтвердити пару");
        SentMessage requesterNotice = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 201L)
                .reduce((first, second) -> second)
                .orElseThrow();
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(requesterNotice.keyboard(), "Скасувати запит"),
                201L,
                201L,
                "cancel"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Запросити пару"),
                201L,
                201L,
                "choose-2"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Sam"),
                201L,
                201L,
                "request-2"));
        ExchangeService exchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        bot.onUpdateReceived(callbackUpdate(staleAccept, 202L, 202L, "stale-accept"));

        assertNull(exchangeService.participant(201L).orElseThrow().partnerId());
        assertNull(exchangeService.participant(202L).orElseThrow().partnerId());
        assertEquals(
                List.of(new ExchangeState.PairRequest(201L, 202L)),
                exchangeService.state().pairRequests());
        assertTrue(messenger.callbackAnswers.getLast().text().contains("неактивна"));
    }

    @Test
    void participantCanDeclineOnlyFromTheirPrivateChat() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Запросити пару"),
                201L,
                201L,
                "choose"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Sam"),
                201L,
                201L,
                "request"));
        SentMessage recipientNotice = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 202L)
                .reduce((first, second) -> second)
                .orElseThrow();
        String decline = callbackDataFor(recipientNotice.keyboard(), "Відхилити");
        ExchangeService exchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        bot.onUpdateReceived(callbackUpdate(decline, 202L, -900L, "group"));
        assertEquals(1, exchangeService.state().pairRequests().size());
        assertTrue(messenger.callbackAnswers.getLast().text().contains("особистому чаті"));

        bot.onUpdateReceived(callbackUpdate(decline, 202L, 202L, "decline"));
        ExchangeService declinedExchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertTrue(declinedExchangeService.state().pairRequests().isEmpty());
        assertNull(declinedExchangeService.participant(201L).orElseThrow().partnerId());
        assertNull(declinedExchangeService.participant(202L).orElseThrow().partnerId());
    }

    @Test
    void organizerCanOpenPairManagementFromThePrivateMenu() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        String rosterButton = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Список учасників");
        String managePairs = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Керувати парами");
        bot.onUpdateReceived(callbackUpdate(rosterButton, 100L, 100L, "roster"));
        assertEquals(100L, messenger.sentMessages.getLast().chatId());
        assertTrue(messenger.sentMessages.getLast().text().contains("Alex"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Sam"));

        bot.onUpdateReceived(callbackUpdate(managePairs, 100L, 100L, "manage"));

        assertTrue(messenger.sentMessages.getLast().text().contains("першого учасника"));
        assertTrue(messenger.sentMessages.getLast().keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().equals("Alex")));
    }

    @Test
    void organizerPairChangesPrivatelyNotifyEveryAffectedParticipant() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 204L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        pairParticipants(bot, messenger, "Guest 201", "Guest 202");
        pairParticipants(bot, messenger, "Guest 203", "Guest 204");
        int firstNotificationIndex = messenger.sentMessages.size();

        pairParticipants(bot, messenger, "Guest 201", "Guest 203");

        List<SentMessage> notifications =
                messenger.sentMessages.subList(firstNotificationIndex, messenger.sentMessages.size());
        for (long userId : List.of(201L, 202L, 203L, 204L)) {
            assertTrue(notifications.stream().anyMatch(message -> message.chatId() == userId));
        }
        assertTrue(notifications.stream().allMatch(message ->
                !message.text().contains("Твоя ціль")
                        && !message.text().contains("купити подарунок")));
    }

    @Test
    void failedRunDoesNotReportSuccessOrClearRosterAndCanBeRetried() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        messenger.failChatId = 202L;

        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));

        String failureNotice = messenger.sentMessages.getLast().text();
        assertTrue(failureNotice.contains("повтори `/run`"));
        assertFalse(failureNotice.contains("успішно завершено"));
        assertTrue(new PendingDrawStore(dataDirectory, TEST_DATA_KEY).load().isPresent());
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Guest 203"));

        bot.onUpdateReceived(update(100L, 100L, "private", "/open"));
        assertTrue(messenger.sentMessages.getLast().text().contains("незавершений розподіл"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        assertTrue(messenger.sentMessages.getLast().text().contains("змінювати пари не можна"));

        messenger.failChatId = null;
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));

        assertTrue(messenger.sentMessages.getLast().text().contains("успішно завершено"));
        assertTrue(new PendingDrawStore(dataDirectory, TEST_DATA_KEY).load().isEmpty());
    }

    private OrganizerService createService() {
        return new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
    }

    private SecretSantaBot createBot(
            OrganizerService organizerService, List<SentMessage> sentMessages) {
        return new SecretSantaBot(
                "test-token",
                "test-bot",
                organizerService,
                new TelegramMessenger() {
                    @Override
                    public void sendMessage(
                            long chatId, String text, InlineKeyboardMarkup keyboard) {
                        sentMessages.add(new SentMessage(chatId, text, keyboard));
                    }

                    @Override
                    public void answerCallback(String callbackQueryId, String text) {}
                },
                testPendingDrawStore());
    }

    private SecretSantaBot createBot(
            OrganizerService organizerService, TestTelegramMessenger messenger) {
        return new SecretSantaBot(
                "test-token", "test-bot", organizerService, messenger, testPendingDrawStore());
    }

    private PendingDrawStore testPendingDrawStore() {
        return new PendingDrawStore(dataDirectory, TEST_DATA_KEY);
    }

    private static String openRegistration(
            SecretSantaBot bot, TestTelegramMessenger messenger) {
        bot.onUpdateReceived(update(100L, 100L, "private", "/claim setup-code"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/open"));
        return inviteCode(messenger.sentMessages.getLast().text());
    }

    private static String openRegistrationWithButtons(
            SecretSantaBot bot, TestTelegramMessenger messenger) {
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:claim", 100L, 100L, "claim", "Organizer"));
        bot.onUpdateReceived(update(100L, 100L, "private", "setup-code"));
        String openCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Відкрити реєстрацію");
        bot.onUpdateReceived(callbackUpdate(
                openCallback, 100L, 100L, "open", "Organizer"));
        return inviteCode(messenger.sentMessages.getLast().text());
    }

    private static String inviteCode(String message) {
        return message.substring(message.lastIndexOf("Запрошувальний код: ")
                        + "Запрошувальний код: ".length())
                .replace("`", "");
    }

    private static void pairParticipants(
            SecretSantaBot bot,
            TestTelegramMessenger messenger,
            String firstName,
            String secondName) {
        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        String firstChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), firstName);
        bot.onUpdateReceived(callbackUpdate(firstChoice, 100L, 100L, "first-" + firstName));

        String secondChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), secondName);
        bot.onUpdateReceived(callbackUpdate(secondChoice, 100L, 100L, "second-" + secondName));
    }

    private static String callbackDataFor(InlineKeyboardMarkup keyboard, String buttonText) {
        return keyboard.getKeyboard().stream()
                .flatMap(List::stream)
                .filter(button -> button.getText().equals(buttonText))
                .map(InlineKeyboardButton::getCallbackData)
                .findFirst()
                .orElseThrow();
    }

    private static Update callbackUpdate(
            String callbackData, long userId, long chatId, String callbackId) {
        return callbackUpdate(callbackData, userId, chatId, callbackId, "User " + userId);
    }

    private static Update callbackUpdate(
            String callbackData, long userId, long chatId, String callbackId, String firstName) {
        User user = new User();
        user.setId(userId);
        user.setFirstName(firstName);

        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType(chatId == userId ? "private" : "group");

        Message message = new Message();
        message.setChat(chat);

        CallbackQuery callback = new CallbackQuery();
        callback.setId(callbackId);
        callback.setFrom(user);
        callback.setMessage(message);
        callback.setData(callbackData);

        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private static Update update(long chatId, long userId, String chatType, String text) {
        return update(chatId, userId, chatType, text, "User " + userId);
    }

    private static Update update(
            long chatId, long userId, String chatType, String text, String firstName) {
        User user = new User();
        user.setId(userId);
        user.setFirstName(firstName);

        Chat chat = new Chat();
        chat.setId(chatId);
        chat.setType(chatType);

        Message message = new Message();
        message.setFrom(user);
        message.setChat(chat);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private record SentMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {}

    private record CallbackAnswer(String callbackId, String text) {}

    private static final class TestTelegramMessenger implements TelegramMessenger {
        private final List<SentMessage> sentMessages = new ArrayList<>();
        private final List<CallbackAnswer> callbackAnswers = new ArrayList<>();

        private Long failChatId;

        @Override
        public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard)
                throws TelegramApiException {
            sentMessages.add(new SentMessage(chatId, text, keyboard));
            if (failChatId != null && failChatId == chatId) {
                throw new TelegramApiException("Simulated delivery failure.");
            }
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {
            callbackAnswers.add(new CallbackAnswer(callbackQueryId, text));
        }
    }
}
