package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                "(?s).*Запрошувальний код: [A-Za-z0-9_-]{32}.*"));
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
        assertTrue(messenger.sentMessages.getLast().text().contains("Поки що ніхто"));
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
        assertTrue(messenger.sentMessages.getLast().text().contains("Поки що ніхто"));
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

    private static String inviteCode(String message) {
        return message.substring(message.lastIndexOf("Запрошувальний код: ")
                + "Запрошувальний код: ".length());
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
        User user = new User();
        user.setId(userId);

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
        User user = new User();
        user.setId(userId);

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
