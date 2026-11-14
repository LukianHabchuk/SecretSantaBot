package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

class SecretSantaBotTest {
    private static final byte[] TEST_DATA_KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void usersChooseChangeAndDeleteTheirLanguageAcrossRestarts() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        LanguageStore languages = new LanguageStore(dataDirectory, TEST_DATA_KEY);
        SecretSantaBot bot = new SecretSantaBot(
                "test-token", "test-bot", createService(), messenger,
                testPendingDrawStore(), languages);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        assertEquals(BotButtons.LANGUAGE_ENGLISH,
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "English"));
        bot.onUpdateReceived(callbackUpdate(BotButtons.LANGUAGE_ENGLISH, 201L, -100L, "group"));
        assertTrue(languages.get(201L).isEmpty());
        bot.onUpdateReceived(callbackUpdate(BotButtons.LANGUAGE_ENGLISH, 201L, 201L, "english"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Secret Santa"));
        SetMyCommands englishMenu = messenger.commandMenus.stream()
                .filter(menu -> menu.getScope() instanceof BotCommandScopeChat scope
                        && scope.getChatId().equals("201"))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals("Open menu", englishMenu.getCommands().getFirst().getDescription());
        assertTrue(englishMenu.getCommands().stream()
                .anyMatch(command -> command.getCommand().equals("language")));

        LanguageStore restartedLanguages = new LanguageStore(dataDirectory, TEST_DATA_KEY);
        SecretSantaBot restarted = new SecretSantaBot(
                "test-token", "test-bot", createService(), messenger,
                testPendingDrawStore(), restartedLanguages);
        restarted.onUpdateReceived(update(201L, 201L, "private", "/start"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Secret Santa"));

        restarted.onUpdateReceived(update(201L, 201L, "private", "/language"));
        restarted.onUpdateReceived(callbackUpdate(BotButtons.LANGUAGE_UKRAINIAN, 201L, 201L, "ua"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Таємний Санта"));
        SetMyCommands ukrainianMenu = messenger.commandMenus.stream()
                .filter(menu -> menu.getScope() instanceof BotCommandScopeChat scope
                        && scope.getChatId().equals("201"))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals("Відкрити меню", ukrainianMenu.getCommands().getFirst().getDescription());
        restarted.onUpdateReceived(update(201L, 201L, "private", "/forgetlanguage"));
        assertTrue(restartedLanguages.get(201L).isEmpty());
        restarted.onUpdateReceived(update(201L, 201L, "private", "/start"));
        assertEquals(BotButtons.LANGUAGE_ENGLISH,
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "English"));
    }

    @Test
    void pairNotificationsUseEachRecipientsSavedLanguage() throws Exception {
        LanguageStore languages = new LanguageStore(dataDirectory, TEST_DATA_KEY);
        languages.set(201L, BotLanguage.ENGLISH);
        languages.set(202L, BotLanguage.UKRAINIAN);
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = new SecretSantaBot(
                "test-token", "test-bot", createService(), messenger,
                testPendingDrawStore(), languages);
        String invite = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + invite + " Олена"));
        assertTrue(messenger.sentMessages.getLast().text().contains("You joined"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + invite + " Sam"));

        bot.onUpdateReceived(callbackUpdate(
                BotButtons.PAIR_REQUEST_CHOOSE, 202L, 202L, "choose"));
        String target = callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Олена");
        bot.onUpdateReceived(callbackUpdate(target, 202L, 202L, "request"));

        assertTrue(messenger.sentMessages.stream().anyMatch(message ->
                message.chatId() == 201L
                        && message.text().contains("pair request from **Sam**")));
        assertTrue(messenger.sentMessages.stream().anyMatch(message ->
                message.chatId() == 202L
                        && message.text().contains("Твій запит")));

        String accept = callbackDataFor(
                messenger.sentMessages.stream()
                        .filter(message -> message.chatId() == 201L)
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .keyboard(),
                "Accept pair");
        bot.onUpdateReceived(callbackUpdate(accept, 201L, 201L, "accept"));

        assertTrue(messenger.sentMessages.stream().anyMatch(message ->
                message.chatId() == 201L
                        && message.text().contains(
                                "This pair only means neither of you will be assigned to the other in the draw.")));
        assertTrue(messenger.sentMessages.stream().anyMatch(message ->
                message.chatId() == 202L
                        && message.text().contains(
                                "Ця пара лише означає, що ви не отримаєте одне одного в розіграші.")));
    }

    @Test
    void logsOnlySafeActionLabelsWithoutInputOrAssignmentAttempts() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        List<String> events = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.INFO) {
                    events.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Logger commandLogger = Logger.getLogger(BotCommandController.class.getName());
        Logger buttonLogger = Logger.getLogger(BotButtonController.class.getName());
        commandLogger.addHandler(handler);
        buttonLogger.addHandler(handler);
        try {
            bot.onUpdateReceived(update(201L, 201L, "private", "/claim sensitive-setup-code"));
            bot.onUpdateReceived(callbackUpdate("onboarding:join", 201L, 201L, "join", "Alex"));
            bot.onUpdateReceived(update(201L, 201L, "private", "sensitive-invite", "Alex"));
            bot.onUpdateReceived(callbackUpdate(
                    "onboarding:join:confirm:sensitive-token", 201L, 201L, "confirm", "Alex"));
            bot.onUpdateReceived(update(201L, 201L, "private", "/run"));
            bot.onUpdateReceived(callbackUpdate(
                    "organizer:run-draw", 201L, 201L, "draw", "Alex"));
            bot.onUpdateReceived(update(201L, 201L, "private", "/unknown-sensitive"));

            assertTrue(events.contains("action_attempted command_claim"));
            assertTrue(events.contains("action_attempted button_join"));
            assertTrue(events.contains("action_attempted input_invite_code"));
            assertTrue(events.contains("action_attempted button_join_confirm"));
            assertTrue(events.stream().noneMatch(event ->
                    event.contains("sensitive") || event.contains("Alex")
                            || event.contains("201") || event.contains("run")
                            || event.contains("draw")));
        } finally {
            commandLogger.removeHandler(handler);
            buttonLogger.removeHandler(handler);
        }
    }

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
    void startHidesOrganizerClaimAfterItHasBeenClaimed() throws Exception {
        OrganizerService organizerService = createService();
        organizerService.claim(100L, "setup-code");
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));

        SentMessage menu = messenger.sentMessages.getLast();
        assertTrue(menu.keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .noneMatch(button -> button.getCallbackData().equals("onboarding:claim")));
        assertTrue(menu.keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getCallbackData().equals("onboarding:join")));
        assertFalse(menu.text().contains("Створи обмін"));
    }

    @Test
    void startupPublishesSeparateParticipantAndExistingOrganizerCommandMenus() throws Exception {
        OrganizerService organizerService = createService();
        organizerService.claim(100L, "setup-code");
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.initializeCommandMenus();

        assertEquals(2, messenger.commandMenus.size());
        SetMyCommands participants = messenger.commandMenus.get(0);
        assertTrue(participants.getScope() instanceof BotCommandScopeAllPrivateChats);
        assertEquals(List.of("start", "join", "register"),
                participants.getCommands().stream().map(BotCommand::getCommand).toList());
        SetMyCommands organizer = messenger.commandMenus.get(1);
        assertTrue(organizer.getScope() instanceof BotCommandScopeChat);
        assertEquals("100", ((BotCommandScopeChat) organizer.getScope()).getChatId());
        assertEquals(List.of("start", "open", "list", "pair", "run"),
                organizer.getCommands().stream().map(BotCommand::getCommand).toList());
    }

    @Test
    void successfulOrganizerClaimUpdatesTheCommandMenu() {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        assertTrue(messenger.commandMenus.getLast().getCommands().stream()
                .anyMatch(command -> command.getCommand().equals("claim")));

        bot.onUpdateReceived(update(100L, 100L, "private", "/claim setup-code"));

        SetMyCommands participantMenu = messenger.commandMenus.stream()
                .filter(menu -> menu.getScope() instanceof BotCommandScopeAllPrivateChats)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(participantMenu.getCommands().stream()
                .noneMatch(command -> command.getCommand().equals("claim")));
        assertTrue(messenger.commandMenus.stream().anyMatch(menu ->
                menu.getScope() instanceof BotCommandScopeChat scope
                        && scope.getChatId().equals("100")
                        && menu.getCommands().stream()
                                .anyMatch(command -> command.getCommand().equals("open"))));
    }

    @Test
    void organizerClaimThroughButtonsUpdatesTheCommandMenu() {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                "onboarding:claim", 100L, 100L, "claim", "Organizer"));

        bot.onUpdateReceived(update(100L, 100L, "private", "setup-code"));

        assertTrue(messenger.commandMenus.stream().anyMatch(menu ->
                menu.getScope() instanceof BotCommandScopeChat scope
                        && scope.getChatId().equals("100")
                        && menu.getCommands().stream()
                                .anyMatch(command -> command.getCommand().equals("open"))));
    }

    @Test
    void failedCommandMenuPublicationDoesNotBlockStartAndIsRetried() {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        messenger.failCommandMenus = true;
        SecretSantaBot bot = createBot(createService(), messenger);

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        assertTrue(messenger.sentMessages.getLast().keyboard() != null);
        messenger.failCommandMenus = false;
        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));

        assertTrue(messenger.commandMenus.stream().anyMatch(menu ->
                menu.getScope() instanceof BotCommandScopeAllPrivateChats));
    }

    @Test
    void rejectedCommandMenuUpdateDoesNotUndoClaimAndRetriesOnStart() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        messenger.rejectCommandMenus = true;
        SecretSantaBot bot = createBot(organizerService, messenger);

        bot.onUpdateReceived(update(100L, 100L, "private", "/claim setup-code"));

        assertTrue(organizerService.isOrganizer(100L));
        assertTrue(messenger.sentMessages.getLast().text().contains("організатор"));
        messenger.rejectCommandMenus = false;
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        assertTrue(messenger.commandMenus.stream()
                .anyMatch(menu -> menu.getScope() instanceof BotCommandScopeChat));
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
    void registeredParticipantCanEditTheirNameInPrivateChat() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));

        String editCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити моє ім'я");
        bot.onUpdateReceived(callbackUpdate(editCallback, 201L, -100L, "group-edit-name"));
        assertTrue(messenger.callbackAnswers.getLast().text().contains("особистому чаті"));
        bot.onUpdateReceived(callbackUpdate(editCallback, 201L, 201L, "edit-name"));
        bot.onUpdateReceived(update(201L, 201L, "private", "  "));
        assertTrue(messenger.sentMessages.getLast().text().contains("Ім'я має містити"));
        bot.onUpdateReceived(update(201L, 201L, "private", "  Alex Updated  "));

        ExchangeState state = new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals("Alex Updated", state.participants().stream()
                .filter(participant -> participant.userId() == 201L)
                .findFirst().orElseThrow().name());
        assertTrue(messenger.sentMessages.stream().anyMatch(message ->
                message.chatId() == 201L && message.text().contains("Ім'я оновлено")));
    }

    @Test
    void organizerCanEditTheIntendedParticipantWhenNamesAreDuplicate() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));

        String editCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити ім'я учасника");
        bot.onUpdateReceived(callbackUpdate(editCallback, 100L, 100L, "edit-names"));
        String secondParticipant = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Alex (2)");
        bot.onUpdateReceived(callbackUpdate(
                secondParticipant, 100L, 100L, "select-second-participant"));
        bot.onUpdateReceived(update(100L, 100L, "private", "Sam"));

        ExchangeState state = new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals("Alex", state.participants().stream()
                .filter(participant -> participant.userId() == 201L)
                .findFirst().orElseThrow().name());
        assertEquals("Sam", state.participants().stream()
                .filter(participant -> participant.userId() == 202L)
                .findFirst().orElseThrow().name());
    }

    @Test
    void participantCannotStartOrganizerNameEditing() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));

        bot.onUpdateReceived(callbackUpdate(
                BotButtons.ORGANIZER_EDIT_NAMES, 201L, 201L, "unauthorized-edit-names"));

        assertTrue(messenger.callbackAnswers.getLast().text().contains("лише організатору"));
        ExchangeState state = new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals("Alex", state.participants().stream()
                .filter(participant -> participant.userId() == 201L)
                .findFirst().orElseThrow().name());
    }

    @Test
    void staleParticipantNameEditButtonCannotRenameAfterDrawStarts() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));
        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        String staleEditCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити моє ім'я");

        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));
        bot.onUpdateReceived(callbackUpdate(
                staleEditCallback, 201L, 201L, "stale-name-edit"));
        bot.onUpdateReceived(update(201L, 201L, "private", "Changed"));

        ExchangeState state = new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals("Alex", state.participants().stream()
                .filter(participant -> participant.userId() == 201L)
                .findFirst().orElseThrow().name());
    }

    @Test
    void organizerIsNotifiedPrivatelyOnceWhenParticipantJoinsThroughButtons() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistrationWithButtons(bot, messenger);
        int beforeJoin = messenger.sentMessages.size();

        bot.onUpdateReceived(callbackUpdate(
                "onboarding:join", 201L, 201L, "join", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", inviteCode, "Alex"));
        String editCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Змінити ім'я");
        bot.onUpdateReceived(callbackUpdate(editCallback, 201L, 201L, "edit", "Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", " Alex_* ", "Alex"));
        String confirmCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Підтвердити ім'я");
        bot.onUpdateReceived(callbackUpdate(confirmCallback, 201L, 201L, "confirm", "Alex"));
        bot.onUpdateReceived(callbackUpdate(confirmCallback, 201L, 201L, "repeat", "Alex"));

        List<SentMessage> organizerMessages = messenger.sentMessages.subList(
                        beforeJoin, messenger.sentMessages.size()).stream()
                .filter(message -> message.chatId() == 100L)
                .toList();
        assertEquals(1, organizerMessages.size());
        assertTrue(organizerMessages.getFirst().text().contains("Alex\\_\\*"));
        assertTrue(messenger.sentMessages.stream()
                .noneMatch(message -> message.chatId() != 100L
                        && message.text().contains("Alex\\_\\*")
                        && message.text().contains("приєднався")));
    }

    @Test
    void legacyJoinNotifiesOrganizerOnlyForSuccessfulRegistration() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        int beforeJoin = messenger.sentMessages.size();

        bot.onUpdateReceived(update(201L, 201L, "private", "/join invalid Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));

        List<SentMessage> organizerMessages = messenger.sentMessages.subList(
                        beforeJoin, messenger.sentMessages.size()).stream()
                .filter(message -> message.chatId() == 100L)
                .toList();
        assertEquals(1, organizerMessages.size());
        assertTrue(organizerMessages.getFirst().text().contains("Alex"));
    }

    @Test
    void organizerNotificationFailureDoesNotUndoParticipantRegistration() throws Exception {
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(createService(), messenger);
        String inviteCode = openRegistration(bot, messenger);
        messenger.failChatId = 100L;

        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));

        assertTrue(messenger.sentMessages.getLast().text().contains("приєднався"));
        assertEquals(201L, messenger.sentMessages.getLast().chatId());
        ExchangeState state = new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertTrue(state.participants().stream().anyMatch(participant -> participant.userId() == 201L));
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

        List<SentMessage> participantMessages = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 201L)
                .toList();
        assertTrue(participantMessages.get(0).text().contains("приєднався"));
        assertTrue(participantMessages.get(1).text().contains("вже зареєстрований"));
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
                .anyMatch(message -> message.text().contains("Пару підтверджено")
                        && message.text().contains(
                                "Ця пара лише означає, що ви не отримаєте одне одного в розіграші.")));
        assertTrue(cancel.startsWith("pair-request:cancel:"));

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Мій запит"),
                201L,
                201L,
                "status"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Твоя пара"));
        assertTrue(messenger.sentMessages.getLast().text().contains(
                "Ця пара лише означає, що ви не отримаєте одне одного в розіграші."));
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
        for (long userId : List.of(201L, 203L)) {
            assertTrue(notifications.stream().anyMatch(message ->
                    message.chatId() == userId
                            && message.text().contains(
                                    "Ця пара лише означає, що ви не отримаєте одне одного в розіграші.")));
        }
    }

    @Test
    void organizerPairingNotifiesParticipantWhoseOpenRequestWasRemoved() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }

        bot.onUpdateReceived(update(201L, 201L, "private", "/start"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Запросити пару"),
                201L,
                201L,
                "choose"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Guest 202"),
                201L,
                201L,
                "request"));
        ExchangeService exchangeService =
                new ExchangeService(organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(
                List.of(new ExchangeState.PairRequest(201L, 202L)),
                exchangeService.state().pairRequests());
        assertNull(exchangeService.participant(201L).orElseThrow().partnerId());
        assertNull(exchangeService.participant(202L).orElseThrow().partnerId());
        int notificationsBeforePairing = messenger.sentMessages.size();

        pairParticipants(bot, messenger, "Guest 201", "Guest 203");

        List<SentMessage> notifications =
                messenger.sentMessages.subList(notificationsBeforePairing, messenger.sentMessages.size());
        assertTrue(notifications.stream()
                .anyMatch(message -> message.chatId() == 202L
                        && message.text().contains("запит на пару скасовано")));
        assertTrue(new ExchangeService(
                        organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY))
                .state().pairRequests().isEmpty());
    }

    @Test
    void staleOrganizerPairButtonCannotChangePairsAfterRegistrationCloses() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        bot.onUpdateReceived(update(201L, 201L, "private", "/join " + inviteCode + " Alex"));
        bot.onUpdateReceived(update(202L, 202L, "private", "/join " + inviteCode + " Sam"));

        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        bot.onUpdateReceived(callbackUpdate(
                callbackDataFor(messenger.sentMessages.getLast().keyboard(), "Alex"),
                100L,
                100L,
                "first"));
        String staleSecondChoice = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Sam");
        messenger.failChatId = 202L;
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));

        assertDoesNotThrow(() -> bot.onUpdateReceived(callbackUpdate(
                staleSecondChoice, 100L, 100L, "stale-second")));
        assertTrue(messenger.callbackAnswers.getLast().text()
                .contains(BotMessages.PAIRING_CLOSED_CALLBACK));
        ExchangeService closedExchangeService = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(
                ExchangeState.Phase.DELIVERING,
                closedExchangeService.state().phase());
        assertNull(closedExchangeService.participant(201L).orElseThrow().partnerId());
        assertNull(closedExchangeService.participant(202L).orElseThrow().partnerId());
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
        assertTrue(new PendingDrawStore(dataDirectory, TEST_DATA_KEY).load().isEmpty());
        ExchangeState pendingState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(ExchangeState.Phase.DELIVERING, pendingState.phase());
        assertEquals(4, pendingState.assignments().size());
        bot.onUpdateReceived(update(100L, 100L, "private", "/list"));
        assertTrue(messenger.sentMessages.getLast().text().contains("Guest 203"));

        bot.onUpdateReceived(update(100L, 100L, "private", "/open"));
        assertTrue(messenger.sentMessages.getLast().text().contains("активний обмін"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/pair"));
        assertTrue(messenger.sentMessages.getLast().text().contains("змінювати пари не можна"));

        messenger.failChatId = null;
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));

        assertTrue(messenger.sentMessages.getLast().text().contains("успішно завершено"));
        assertTrue(new PendingDrawStore(dataDirectory, TEST_DATA_KEY).load().isEmpty());
        ExchangeState completedState =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, completedState.phase());
        assertEquals(4, completedState.participants().size());
        assertEquals(4, completedState.assignments().size());
    }

    @Test
    void participantCanConfirmReceiptFromTheirPrivateAssignmentMessage() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }

        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));

        ExchangeState draw = new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                .load()
                .orElseThrow();
        SentMessage assignment = messenger.sentMessages.stream()
                .filter(message -> message.chatId() == 201L)
                .filter(message -> message.keyboard() != null)
                .findFirst()
                .orElseThrow();
        assertTrue(assignment.keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().contains("Отримав")));
        String callback = callbackDataFor(
                assignment.keyboard(), "Отримав подарункове завдання");

        bot.onUpdateReceived(callbackUpdate(
                "draw:received:" + (draw.drawGeneration() + 1),
                202L,
                202L,
                "stale-receipt"));
        assertTrue(messenger.callbackAnswers.getLast().text().contains("неактивне"));

        bot.onUpdateReceived(callbackUpdate(callback, 201L, 201L, "received"));

        ExchangeState confirmed = new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                .load()
                .orElseThrow();
        assertEquals(draw.drawGeneration(), confirmed.drawGeneration());
        assertEquals(java.util.Set.of(201L), confirmed.confirmedUserIds());
    }

    @Test
    void organizerCanStartTheDrawFromThePrivateMenu() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));

        String drawCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Провести жеребкування");
        bot.onUpdateReceived(callbackUpdate(drawCallback, 100L, 100L, "draw"));

        ExchangeState state =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, state.phase());
        assertEquals(4, messenger.sentMessages.stream()
                .filter(message -> message.keyboard() != null
                        && message.keyboard().getKeyboard().stream()
                                .flatMap(List::stream)
                                .anyMatch(button -> button.getCallbackData()
                                        .startsWith(BotButtons.DRAW_RECEIVED_PREFIX)))
                .count());
    }

    @Test
    void organizerMustConfirmReplacementAndOldReceiptButtonsBecomeStale() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));
        ExchangeState originalDraw =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        String staleReceipt = callbackDataFor(
                messenger.sentMessages.stream()
                        .filter(message -> message.chatId() == 201L)
                        .filter(message -> message.keyboard() != null)
                        .findFirst()
                        .orElseThrow()
                        .keyboard(),
                "Отримав подарункове завдання");

        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        String replaceCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Провести нове жеребкування");
        bot.onUpdateReceived(callbackUpdate(replaceCallback, 100L, 100L, "replace"));
        assertEquals(
                originalDraw.drawGeneration(),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .drawGeneration());

        String replacementCancel = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Скасувати");
        String cancelledConfirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Так, замінити розподіл");
        bot.onUpdateReceived(callbackUpdate(
                replacementCancel, 100L, 100L, "cancel-replacement"));
        bot.onUpdateReceived(callbackUpdate(
                cancelledConfirmation, 100L, 100L, "stale-replacement"));
        assertEquals(
                originalDraw.drawGeneration(),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .drawGeneration());

        bot.onUpdateReceived(callbackUpdate(replaceCallback, 100L, 100L, "replace-again"));
        String confirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Так, замінити розподіл");
        bot.onUpdateReceived(callbackUpdate(
                confirmation, 201L, 201L, "unauthorized-replacement"));
        assertEquals(
                originalDraw.drawGeneration(),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                        .load()
                        .orElseThrow()
                        .drawGeneration());

        bot.onUpdateReceived(callbackUpdate(confirmation, 100L, 100L, "confirm-replacement"));
        ExchangeState replacement =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(originalDraw.drawGeneration() + 1, replacement.drawGeneration());
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, replacement.phase());
        assertTrue(replacement.confirmedUserIds().isEmpty());

        bot.onUpdateReceived(callbackUpdate(staleReceipt, 201L, 201L, "old-receipt"));
        assertTrue(messenger.callbackAnswers.getLast().text().contains("неактивне"));
        assertTrue(new ExchangeStore(dataDirectory, TEST_DATA_KEY)
                .load()
                .orElseThrow()
                .confirmedUserIds()
                .isEmpty());
    }

    @Test
    void replacementControlIsUnavailableWhileOriginalDeliveryIsIncomplete() throws Exception {
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
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));

        assertFalse(messenger.sentMessages.getLast().keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getCallbackData()
                        .equals(BotButtons.ORGANIZER_REPLACE_DRAW)));
        bot.onUpdateReceived(callbackUpdate(
                BotButtons.ORGANIZER_REPLACE_DRAW, 100L, 100L, "premature-replacement"));

        ExchangeState state =
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow();
        assertEquals(ExchangeState.Phase.DELIVERING, state.phase());
        assertEquals(1, state.drawGeneration());
        assertTrue(messenger.sentMessages.getLast().text().contains("достав"));
    }

    @Test
    void organizerStatusShowsDeliveryAndReceiptProgressWithoutAssignments() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));

        String statusCallback = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Статус обміну");
        bot.onUpdateReceived(callbackUpdate(statusCallback, 100L, 100L, "status"));

        String status = messenger.sentMessages.getLast().text();
        assertTrue(status.contains("4/4"));
        assertTrue(status.contains("0/4"));
        assertTrue(status.contains("User 100"));
        assertFalse(status.contains("Твоя ціль"));
        assertFalse(status.contains("придбати подарунок для"));
    }

    @Test
    void organizerCleanupRequiresEveryReceiptAndASeparateConfirmation() throws Exception {
        OrganizerService organizerService = createService();
        TestTelegramMessenger messenger = new TestTelegramMessenger();
        SecretSantaBot bot = createBot(organizerService, messenger);
        String inviteCode = openRegistration(bot, messenger);
        for (long userId = 201L; userId <= 203L; userId++) {
            bot.onUpdateReceived(update(
                    userId, userId, "private", "/join " + inviteCode + " Guest " + userId));
        }
        bot.onUpdateReceived(update(100L, 100L, "private", "/run"));
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));
        assertFalse(messenger.sentMessages.getLast().keyboard().getKeyboard().stream()
                .flatMap(List::stream)
                .anyMatch(button -> button.getText().equals("Очистити обмін")));

        List<SentMessage> assignmentMessages = messenger.sentMessages.stream()
                .filter(message -> message.keyboard() != null
                        && message.keyboard().getKeyboard().stream()
                                .flatMap(List::stream)
                                .anyMatch(button -> button.getCallbackData()
                                        .startsWith(BotButtons.DRAW_RECEIVED_PREFIX)))
                .toList();
        for (SentMessage message : assignmentMessages) {
            String receipt = callbackDataFor(
                    message.keyboard(), "Отримав подарункове завдання");
            bot.onUpdateReceived(callbackUpdate(
                    receipt, message.chatId(), message.chatId(), "receipt-" + message.chatId()));
        }
        bot.onUpdateReceived(update(100L, 100L, "private", "/start"));

        String cleanupRequest = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Очистити обмін");
        bot.onUpdateReceived(callbackUpdate(cleanupRequest, 100L, 100L, "cleanup"));
        String cleanupConfirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Так, видалити обмін");
        String cleanupCancellation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Скасувати");
        bot.onUpdateReceived(callbackUpdate(
                cleanupCancellation, 100L, 100L, "cancel-cleanup"));
        assertEquals(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow().phase());

        bot.onUpdateReceived(callbackUpdate(cleanupRequest, 100L, 100L, "cleanup-again"));
        cleanupConfirmation = callbackDataFor(
                messenger.sentMessages.getLast().keyboard(), "Так, видалити обмін");
        bot.onUpdateReceived(callbackUpdate(
                cleanupConfirmation, 201L, 201L, "unauthorized-cleanup"));
        assertEquals(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow().phase());

        bot.onUpdateReceived(callbackUpdate(
                cleanupConfirmation, 100L, 100L, "confirm-cleanup"));

        assertEquals(
                ExchangeState.Phase.EMPTY,
                new ExchangeStore(dataDirectory, TEST_DATA_KEY).load().orElseThrow().phase());
        assertTrue(organizerService.isOrganizer(100L));
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

                    @Override
                    public boolean setMyCommands(SetMyCommands commands) { return true; }
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
        private final List<SetMyCommands> commandMenus = new ArrayList<>();

        private Long failChatId;
        private boolean failCommandMenus;
        private boolean rejectCommandMenus;

        @Override
        public boolean setMyCommands(SetMyCommands commands) throws TelegramApiException {
            if (failCommandMenus) {
                throw new TelegramApiException("Simulated command menu failure.");
            }
            commands.validate();
            if (rejectCommandMenus) {
                return false;
            }
            commandMenus.add(commands);
            return true;
        }

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
