package com.secretsanta;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SecretSantaBot extends TelegramLongPollingBot {

    static Logger logger = Logger.getLogger(SecretSantaBot.class.getName());

    private final String botToken;
    private final String botUsername;
    private final OrganizerService organizerService;
    private final ExchangeService exchangeService;
    private final TelegramMessenger telegramMessenger;
    private final PendingDrawService pendingDrawService;
    private Long pendingPairParticipantId;
    private String activePairingSessionToken;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            PendingDrawStore pendingDrawStore) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.organizerService = organizerService;
        this.exchangeService =
                new ExchangeService(organizerService, pendingDrawStore.exchangeStore());
        this.telegramMessenger = new TelegramMessenger() {
            @Override
            public void sendMessage(
                    long chatId, String text, InlineKeyboardMarkup keyboard)
                    throws TelegramApiException {
                sendTelegramMessage(chatId, text, keyboard);
            }

            @Override
            public void answerCallback(String callbackQueryId, String text)
                    throws TelegramApiException {
                answerTelegramCallback(callbackQueryId, text);
            }
        };
        this.pendingDrawService = new PendingDrawService(
                pendingDrawStore, exchangeService, organizerService, telegramMessenger);
    }

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            TelegramMessenger telegramMessenger,
            PendingDrawStore pendingDrawStore) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.organizerService = organizerService;
        this.exchangeService =
                new ExchangeService(organizerService, pendingDrawStore.exchangeStore());
        this.telegramMessenger = Objects.requireNonNull(telegramMessenger);
        this.pendingDrawService = new PendingDrawService(
                pendingDrawStore, exchangeService, organizerService, telegramMessenger);
    }

    public record Participant(long chatId, String name, Long partnerId) {}

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public synchronized void onUpdateReceived(Update update) {
        if (update == null) {
            return;
        }
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
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
        String[] parts = messageText.split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        String arguments = parts.length > 1 ? parts[1].strip() : "";

        // Обрізаємо @botname, якщо команда надіслана в груповому чаті
        if (command.contains("@")) {
            command = command.split("@")[0];
        }

        switch (command) {
            case "/start":
                sendText(chatId, "🎅 **Таємний Санта**\n\n" +
                        "Організатор: `/claim <код>` — отримати права, `/open` — відкрити реєстрацію.\n" +
                        "Учасники: `/join <код> <ім'я>`.\n" +
                        "Організатор: `/list` — список, `/pair` — створити пари, `/run` — жеребкування.");
                break;

            case "/claim":
                if (!privateChat) {
                    sendText(chatId, "❌ Команду можна виконати лише в особистому чаті.");
                    break;
                }
                if (arguments.isBlank()) {
                    sendText(chatId, "❌ Вкажи код налаштування: `/claim <код>`.");
                    break;
                }
                try {
                    OrganizerService.ClaimResult result = organizerService.claim(userId, arguments);
                    if (result == OrganizerService.ClaimResult.CLAIMED) {
                        sendText(chatId, "✅ Тепер ти організатор.");
                    } else {
                        sendText(chatId,
                                "❌ Не вдалося отримати права організатора. Перевір код або звернись до власника.");
                    }
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not persist organizer claim.", exception);
                    sendText(chatId, "❌ Не вдалося зберегти права організатора. Спробуй пізніше.");
                }
                break;

            case "/open":
                if (!privateChat) {
                    sendText(chatId, "❌ Команду можна виконати лише в особистому чаті.");
                    break;
                }
                if (!arguments.isBlank()) {
                    sendText(chatId, "❌ Використання: `/open`.");
                    break;
                }
                try {
                    String inviteCode = openRegistration(
                            userId, message.getFrom().getFirstName());
                    sendText(chatId, "✅ Реєстрацію відкрито.\nЗапрошувальний код: " + inviteCode);
                } catch (PendingDrawService.PendingDrawInProgressException exception) {
                    sendText(chatId,
                            "❌ Є незавершений розподіл. Повтори `/run`; нову реєстрацію відкрити не можна.");
                } catch (IllegalArgumentException exception) {
                    sendText(chatId, "❌ Не вдалося використати ім'я з профілю Telegram.");
                } catch (SecurityException exception) {
                    sendText(chatId, "❌ Лише організатор може відкрити реєстрацію.");
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not read organizer state.", exception);
                    sendText(chatId, "❌ Не вдалося відкрити реєстрацію. Спробуй пізніше.");
                }
                break;

            case "/join":
                if (!privateChat) {
                    sendText(chatId, "❌ Приєднатися можна лише в особистому чаті.");
                    break;
                }
                String[] joinArguments = arguments.split("\\s+", 2);
                if (joinArguments.length < 2) {
                    sendText(chatId, "❌ Використання: `/join <код> <ім'я>`.");
                    break;
                }
                ExchangeService.RegistrationResult registrationResult;
                try {
                    registrationResult =
                            exchangeService.register(userId, joinArguments[0], joinArguments[1]);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not persist participant registration.", exception);
                    sendText(chatId, "❌ Не вдалося зберегти реєстрацію. Спробуй пізніше.");
                    break;
                }
                switch (registrationResult) {
                    case REGISTERED:
                        sendText(chatId, "✅ Ти приєднався до обміну.");
                        break;
                    case ALREADY_REGISTERED:
                        sendText(chatId, "ℹ️ Ти вже зареєстрований у цьому обміні.");
                        break;
                    case INVALID_NAME:
                        sendText(chatId,
                                "❌ Ім'я має містити від 1 до 64 символів без керівних символів.");
                        break;
                    case INVALID_INVITE:
                        sendText(chatId, "❌ Код запрошення недійсний.");
                        break;
                    case REGISTRATION_CLOSED:
                        sendText(chatId, "❌ Реєстрація зараз закрита.");
                        break;
                }
                break;

            case "/register":
                sendText(chatId,
                        "ℹ️ Щоб приєднатися, скористайся `/join <код> <ім'я>`.");
                break;

            case "/list":
                if (!requireOrganizer(userId, chatId, privateChat)) {
                    break;
                }
                List<Participant> roster = exchangeService.participants();
                if (roster.isEmpty()) {
                    sendText(chatId, "Поки що ніхто не зареєструвався.");
                } else {
                    StringBuilder sb = new StringBuilder("📋 **Список учасників:**\n");
                    Map<Long, String> participantNames = new HashMap<>();
                    roster.forEach(participant ->
                            participantNames.put(participant.chatId(), participant.name()));
                    roster.forEach(p ->
                            sb.append("• ").append(escapeMarkdown(p.name()))
                                    .append(p.partnerId() != null
                                            ? " (пара: `" + escapeMarkdown(
                                                    participantNames.get(p.partnerId())) + "`)"
                                            : "")
                                    .append("\n")
                    );
                    sendText(chatId, sb.toString());
                }
                break;

            case "/run":
                if (requireOrganizer(userId, chatId, privateChat)) {
                    pendingPairParticipantId = null;
                    activePairingSessionToken = null;
                    runSecretSanta(userId);
                }
                break;

            case "/pair":
                if (!requireOrganizer(userId, chatId, privateChat)) {
                    break;
                }
                if (!arguments.isBlank()) {
                    sendText(chatId, "❌ Використання: `/pair`.");
                    break;
                }
                try {
                    if (pendingDrawService.hasPendingDraw()) {
                        pendingPairParticipantId = null;
                        activePairingSessionToken = null;
                        sendText(chatId, "❌ Поки розподіл не завершено, змінювати пари не можна.");
                        break;
                    }
                    startPairing(userId);
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not check pending draw before pairing.", exception);
                    sendText(chatId, "❌ Не вдалося перевірити стан розподілу. Спробуй пізніше.");
                }
                break;

            default:
                sendText(chatId, "Невідома команда. Напиши /start для інструкції.");
        }
    }

    private boolean requireOrganizer(long userId, long chatId, boolean privateChat) {
        if (!privateChat) {
            sendText(chatId, "❌ Команду організатора можна виконати лише в особистому чаті.");
            return false;
        }
        try {
            if (organizerService.isOrganizer(userId)) {
                return true;
            }
            sendText(chatId, "❌ Ця команда доступна лише організатору.");
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not read organizer state.", exception);
            sendText(chatId, "❌ Не вдалося перевірити права організатора. Спробуй пізніше.");
        }
        return false;
    }

    private synchronized String openRegistration(long organizerId, String organizerName)
            throws IOException {
        String inviteCode = pendingDrawService.openRegistration(organizerId, organizerName);
        pendingPairParticipantId = null;
        activePairingSessionToken = null;
        return inviteCode;
    }

    private synchronized void startPairing(long organizerId) {
        List<Participant> roster = exchangeService.participants();
        if (roster.size() < 2) {
            pendingPairParticipantId = null;
            activePairingSessionToken = null;
            sendText(organizerId, "❌ Для створення пари потрібно щонайменше двоє учасників.");
            return;
        }
        pendingPairParticipantId = null;
        byte[] tokenBytes = new byte[6];
        RANDOM.nextBytes(tokenBytes);
        activePairingSessionToken = TOKEN_ENCODER.encodeToString(tokenBytes);
        sendWithKeyboard(
                organizerId,
                "Обери першого учасника:",
                pairingKeyboard(roster, "pair:first:" + activePairingSessionToken + ":", null));
    }

    private synchronized void handleCallbackQuery(CallbackQuery callback) {
        if (callback == null
                || callback.getId() == null
                || callback.getFrom() == null
                || callback.getFrom().getId() == null
                || callback.getMessage() == null) {
            return;
        }

        long userId = callback.getFrom().getId();
        if (!Objects.equals(callback.getMessage().getChatId(), userId)) {
            answerCallback(callback.getId(), "Використовуй кнопки лише в особистому чаті.");
            return;
        }
        try {
            if (!organizerService.isOrganizer(userId)) {
                answerCallback(callback.getId(), "Ця дія доступна лише організатору.");
                return;
            }
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not read organizer state.", exception);
            answerCallback(callback.getId(), "Не вдалося перевірити права організатора.");
            return;
        }

        String[] parts = callback.getData() == null
                ? new String[0]
                : callback.getData().split(":", -1);
        if (parts.length < 4 || parts.length > 5 || !parts[0].equals("pair")) {
            answerCallback(callback.getId(), "Ця кнопка більше неактивна.");
            return;
        }

        boolean firstSelection = parts[1].equals("first") && parts.length == 4;
        boolean secondSelection = parts[1].equals("second") && parts.length == 5;
        if (!firstSelection && !secondSelection) {
            answerCallback(callback.getId(), "Ця кнопка більше неактивна.");
            return;
        }
        if (activePairingSessionToken == null
                || !activePairingSessionToken.equals(parts[2])) {
            answerCallback(callback.getId(), "Список змінився. Почни вибір заново командою `/pair`.");
            return;
        }

        long selectedId;
        long firstId = -1;
        try {
            if (firstSelection) {
                selectedId = Long.parseLong(parts[3]);
            } else {
                firstId = Long.parseLong(parts[3]);
                selectedId = Long.parseLong(parts[4]);
            }
        } catch (NumberFormatException exception) {
            answerCallback(callback.getId(), "Некоректний вибір учасника.");
            return;
        }

        if (secondSelection
                && (pendingPairParticipantId == null || pendingPairParticipantId != firstId)) {
            answerCallback(callback.getId(), "Список змінився. Обери партнера з останнього повідомлення.");
            return;
        }

        Participant selected = exchangeService.participant(selectedId).orElse(null);
        if (selected == null) {
            pendingPairParticipantId = null;
            activePairingSessionToken = null;
            answerCallback(callback.getId(), "Список учасників змінився. Почни з `/pair`.");
            return;
        }

        if (firstSelection) {
            pendingPairParticipantId = selectedId;
            List<Participant> roster = exchangeService.participants().stream()
                    .filter(participant -> participant.chatId() != selectedId)
                    .toList();
            sendWithKeyboard(
                    userId,
                    "Обери партнера для " + escapeMarkdown(selected.name()) + ":",
                    pairingKeyboard(
                            roster,
                            "pair:second:" + activePairingSessionToken + ":"
                                    + selectedId + ":",
                            selectedId));
            answerCallback(callback.getId(), "Обери другого учасника.");
            return;
        }
        if (!secondSelection) {
            answerCallback(callback.getId(), "Почни вибір заново командою `/pair`.");
            return;
        }

        pendingPairParticipantId = null;
        activePairingSessionToken = null;
        try {
            ExchangeService.PairingChange change = exchangeService.pair(firstId, selectedId);
            if (change.unchanged()) {
                sendText(userId, "ℹ️ Ці учасники вже є парою.");
                answerCallback(callback.getId(), "Пара не змінилася.");
            } else {
                sendText(userId, pairingChangeMessage(change));
                answerCallback(callback.getId(), "Пару збережено.");
            }
        } catch (IllegalArgumentException exception) {
            sendText(userId, "❌ Список змінився. Почни вибір заново командою `/pair`.");
            answerCallback(callback.getId(), "Не вдалося створити пару.");
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not persist participant pairing.", exception);
            sendText(userId, "❌ Не вдалося зберегти пару. Спробуй пізніше.");
            answerCallback(callback.getId(), "Не вдалося зберегти пару.");
        }
    }

    private static InlineKeyboardMarkup pairingKeyboard(
            List<Participant> participants, String callbackPrefix, Long excludedParticipantId) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (Participant participant : participants) {
            if (excludedParticipantId != null
                    && participant.chatId() == excludedParticipantId) {
                continue;
            }
            InlineKeyboardButton button = new InlineKeyboardButton();
            button.setText(participant.name());
            button.setCallbackData(callbackPrefix + participant.chatId());
            rows.add(List.of(button));
        }
        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup();
        keyboard.setKeyboard(rows);
        return keyboard;
    }

    private static String pairingChangeMessage(ExchangeService.PairingChange change) {
        StringBuilder message = new StringBuilder("✅ Створено пару: **")
                .append(escapeMarkdown(change.first().name()))
                .append("** та **")
                .append(escapeMarkdown(change.second().name()))
                .append("**.");
        List<String> replacedPairs = new ArrayList<>();
        if (change.formerPartnerOfFirst() != null) {
            replacedPairs.add(
                    escapeMarkdown(change.first().name()) + " + "
                            + escapeMarkdown(change.formerPartnerOfFirst().name()));
        }
        if (change.formerPartnerOfSecond() != null) {
            replacedPairs.add(
                    escapeMarkdown(change.second().name()) + " + "
                            + escapeMarkdown(change.formerPartnerOfSecond().name()));
        }
        if (!replacedPairs.isEmpty()) {
            message.append("\nПопередні пари видалено: ")
                    .append(String.join("; ", replacedPairs))
                    .append(". Звільнені учасники більше не мають пари.");
        }
        return message.toString();
    }

    private void runSecretSanta(long adminChatId) {
        try {
            PendingDrawService.Result result = pendingDrawService.run();
            switch (result.status()) {
                case TOO_FEW_PARTICIPANTS:
                    sendText(adminChatId, "❌ Замало учасників для розподілу (потрібно мінімум 2).");
                    break;
                case PENDING:
                    sendText(
                            adminChatId,
                            "⚠️ Не вдалося доставити результати "
                                    + result.failedDeliveries()
                                    + " учасникам. Розподіл збережено; повтори `/run`, "
                                    + "щоб повторити доставку без нового жеребкування.");
                    break;
                case COMPLETED:
                    sendText(
                            adminChatId,
                            "✅ Розподіл успішно завершено! Усі учасники отримали свої цілі в особисті чати.");
                    break;
            }
        } catch (IllegalStateException exception) {
            sendText(
                    adminChatId,
                    "❌ Не вдалося знайти комбінацію. Перевір пари: для цієї групи немає "
                            + "допустимого розподілу.");
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not safely persist or resume the pending draw.", exception);
            sendText(
                    adminChatId,
                    "❌ Не вдалося безпечно продовжити розподіл. Не відкривай нову реєстрацію; "
                            + "повтори `/run`, щоб відновити поточний розподіл.");
        }
    }

    private void sendText(long chatId, String text) {
        try {
            telegramMessenger.sendMessage(chatId, text, null);
        } catch (TelegramApiException exception) {
            logger.log(Level.SEVERE, "Failed to send Telegram response.", exception);
        }
    }

    private void sendWithKeyboard(
            long chatId, String text, InlineKeyboardMarkup keyboard) {
        try {
            telegramMessenger.sendMessage(chatId, text, keyboard);
        } catch (TelegramApiException exception) {
            logger.log(Level.SEVERE, "Failed to send Telegram response.", exception);
        }
    }

    private void answerCallback(String callbackQueryId, String text) {
        try {
            telegramMessenger.answerCallback(callbackQueryId, text);
        } catch (TelegramApiException exception) {
            logger.log(Level.SEVERE, "Failed to answer Telegram callback.", exception);
        }
    }

    private static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
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
            throw new IllegalStateException("The user home directory is not configured.");
        }
        OrganizerService organizerService = new OrganizerService(
                new OrganizerStore(Path.of(userHome, ".secretsanta-telegram-bot")),
                configuration.setupCode());
        PendingDrawStore pendingDrawStore = new PendingDrawStore(
                Path.of(userHome, ".secretsanta-telegram-bot"), configuration.dataKey());
        try {
            instanceLock = pendingDrawStore.acquireInstanceLock();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not lock the Secret Santa data directory.", exception);
            throw new IllegalStateException(
                    "Another bot may already be using this data directory.", exception);
        }
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new SecretSantaBot(
                configuration.botToken(),
                configuration.botUsername(),
                organizerService,
                pendingDrawStore));
        logger.info("🤖 Бот Таємного Санти успішно запущений і слухає повідомлення!");
    }

    private static volatile PendingDrawStore.InstanceLock instanceLock;
}