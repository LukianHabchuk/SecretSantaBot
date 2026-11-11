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
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

class SecretSantaBotTest {
    @TempDir
    Path dataDirectory;

    @Test
    void participantRetainsRegistrationDetails() {
        SecretSantaBot.Participant participant =
                new SecretSantaBot.Participant(123L, "Alex", "pair-1");

        assertEquals(123L, participant.chatId());
        assertEquals("Alex", participant.name());
        assertEquals("pair-1", participant.coupleId());
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

    private OrganizerService createService() {
        return new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
    }

    private static SecretSantaBot createBot(
            OrganizerService organizerService, List<SentMessage> sentMessages) {
        return new SecretSantaBot(
                "test-token",
                "test-bot",
                organizerService,
                (chatId, text) -> sentMessages.add(new SentMessage(chatId, text)));
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

    private record SentMessage(long chatId, String text) {}
}
