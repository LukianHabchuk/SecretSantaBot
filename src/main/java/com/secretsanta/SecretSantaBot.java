package com.secretsanta;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SecretSantaBot extends TelegramLongPollingBot {

    private static final Logger LOGGER = Logger.getLogger(SecretSantaBot.class.getName());

    private final String botUsername;
    private final BotButtonController buttonController;
    private final BotCommandController commandController;
    private final BotCommandMenu commandMenu;
    private final BotResponder responder;
    private final LanguageSelection languageSelection;

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            PendingDrawStore pendingDrawStore) {
        this(botToken, botUsername, organizerService, null, pendingDrawStore,
                loadLanguageStore(pendingDrawStore));
    }

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            TelegramMessenger telegramMessenger,
            PendingDrawStore pendingDrawStore) {
        this(botToken, botUsername, organizerService, telegramMessenger, pendingDrawStore, null);
    }

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            TelegramMessenger telegramMessenger,
            PendingDrawStore pendingDrawStore,
            LanguageStore languageStore) {
        super(botToken);
        this.botUsername = botUsername;
        TelegramMessenger messenger = telegramMessenger != null ? telegramMessenger : new TelegramMessenger() {
            @Override
            public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard)
                    throws TelegramApiException {
                sendTelegramMessage(chatId, text, keyboard);
            }

            @Override
            public void answerCallback(String callbackQueryId, String text)
                    throws TelegramApiException {
                answerTelegramCallback(callbackQueryId, text);
            }

            @Override
            public boolean setMyCommands(SetMyCommands commands) throws TelegramApiException {
                return Boolean.TRUE.equals(execute(commands));
            }
        };
        ExchangeService exchangeService =
                new ExchangeService(organizerService, pendingDrawStore.exchangeStore());
        PendingDrawService pendingDrawService =
                new PendingDrawService(
                        pendingDrawStore, exchangeService, organizerService, messenger, languageStore);
        this.responder = new BotResponder(messenger, languageStore);
        this.languageSelection = languageStore == null ? null : new LanguageSelection(languageStore, messenger);
        this.commandMenu = new BotCommandMenu(organizerService, messenger, languageStore);
        this.buttonController = new BotButtonController(
                organizerService, exchangeService, pendingDrawService, responder, commandMenu);
        this.commandController = new BotCommandController(
                organizerService, exchangeService, pendingDrawService, buttonController, responder);
    }

    public record Participant(long chatId, String name, Long partnerId) {}

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    void initializeCommandMenus() {
        commandMenu.ensureInitialized();
    }

    @Override
    public synchronized void onUpdateReceived(Update update) {
        if (update == null) {
            return;
        }
        if (update.hasCallbackQuery()) {
            var callback = update.getCallbackQuery();
            if (callback.getFrom() != null && callback.getFrom().getId() != null) {
                responder.beginCallback(callback.getFrom().getId());
            }
            try {
                if (languageSelection != null && callback.getData() != null
                        && callback.getData().startsWith("language:")) {
                    if (callback.getFrom() == null || callback.getFrom().getId() == null
                            || callback.getMessage() == null || callback.getId() == null) {
                        return;
                    }
                    long userId = callback.getFrom().getId();
                    if (!Objects.equals(callback.getMessage().getChatId(), userId)) {
                        responder.answerCallback(callback.getId(), BotMessages.CALLBACK_PRIVATE_ONLY);
                        return;
                    }
                    if (BotButtons.LANGUAGE_CHOOSE.equals(callback.getData())) {
                        languageSelection.prompt(userId);
                        responder.answerCallback(callback.getId(), language -> language == BotLanguage.ENGLISH
                                ? "Choose a language." : "Оберіть мову.");
                    } else if (languageSelection.choose(
                            userId, userId, callback.getData(), callback.getId())) {
                        buttonController.showStartMenu(userId, userId, true);
                    }
                    return;
                }
                buttonController.handleCallbackQuery(callback);
            } finally {
                responder.endCallback();
            }
            return;
        }
        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        if (!message.hasText()
                || message.getFrom() == null
                || message.getFrom().getId() == null
                || message.getChat() == null
                || message.getChatId() == null) {
            return;
        }

        long chatId = message.getChatId();
        long userId = message.getFrom().getId();
        boolean privateChat = Boolean.TRUE.equals(message.getChat().isUserChat());
        String messageText = message.getText().strip();
        if (languageSelection != null && privateChat && chatId == userId) {
            String command = messageText.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
            int separator = command.indexOf('@');
            if (separator >= 0) {
                command = command.substring(0, separator);
            }
            if (command.equals("/language")
                    || (command.equals("/start") && languageSelection.requiresSelection(userId))) {
                languageSelection.prompt(userId);
                return;
            }
            if (command.equals("/forgetlanguage")) {
                BotLanguage previous = responder.languageFor(userId);
                if (languageSelection.forget(userId)) {
                    commandMenu.refreshForUser(userId);
                    responder.sendText(userId, ignored -> previous == BotLanguage.ENGLISH
                            ? "Your language preference was deleted. Use /start to choose again."
                            : "Вибір мови видалено. Скористайтеся /start, щоб обрати знову.");
                }
                return;
            }
        }
        if (privateChat
                && buttonController.isAwaitingInput(userId)
                && !messageText.startsWith("/")) {
            buttonController.handlePendingInput(
                    userId, message.getFrom().getFirstName(), messageText);
            return;
        }
        commandController.handle(
                messageText,
                chatId,
                userId,
                message.getFrom().getFirstName(),
                privateChat);
    }

    private static LanguageStore loadLanguageStore(PendingDrawStore store) {
        try {
            return store.languageStore();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load language preferences.", exception);
        }
    }

    private void sendTelegramMessage(
            long chatId, String text, InlineKeyboardMarkup keyboard)
            throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown");
        if (keyboard != null) {
            message.setReplyMarkup(keyboard);
        }
        execute(message);
    }

    private void answerTelegramCallback(String callbackQueryId, String text)
            throws TelegramApiException {
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        answer.setText(text);
        execute(answer);
    }

    public static void main(String[] args) throws TelegramApiException {
        BotConfiguration configuration = BotConfiguration.from(System.getenv());
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException(BotLogMessages.USER_HOME_UNAVAILABLE);
        }
        OrganizerService organizerService = new OrganizerService(
                new OrganizerStore(Path.of(userHome, ".secretsanta-telegram-bot")),
                configuration.setupCode());
        PendingDrawStore pendingDrawStore = new PendingDrawStore(
                Path.of(userHome, ".secretsanta-telegram-bot"), configuration.dataKey());
        try {
            instanceLock = pendingDrawStore.acquireInstanceLock();
        } catch (IOException exception) {
            LOGGER.log(
                    Level.SEVERE,
                    BotLogMessages.DATA_DIRECTORY_LOCK_FAILED,
                    exception);
            throw new IllegalStateException(
                    BotLogMessages.INSTANCE_LOCK_UNAVAILABLE, exception);
        }
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        SecretSantaBot bot = new SecretSantaBot(
                configuration.botToken(),
                configuration.botUsername(),
                organizerService,
                pendingDrawStore);
        botsApi.registerBot(bot);
        bot.initializeCommandMenus();
        LOGGER.info(BotLogMessages.BOT_STARTED);
    }

    private static volatile PendingDrawStore.InstanceLock instanceLock;
}