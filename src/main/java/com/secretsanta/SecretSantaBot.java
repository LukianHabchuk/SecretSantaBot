package com.secretsanta;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SecretSantaBot extends TelegramLongPollingBot {

    static Logger logger = Logger.getLogger(SecretSantaBot.class.getName());

    private final String botToken;
    private final String botUsername;
    private final OrganizerService organizerService;
    private final TelegramMessageSender messageSender;

    // Зберігаємо учасників у пам'яті: chatId -> Participant
    private final Map<Long, Participant> participants = new ConcurrentHashMap<>();

    SecretSantaBot(
            String botToken, String botUsername, OrganizerService organizerService) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.organizerService = organizerService;
        this.messageSender = this::sendTelegramMessage;
    }

    SecretSantaBot(
            String botToken,
            String botUsername,
            OrganizerService organizerService,
            TelegramMessageSender messageSender) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.organizerService = organizerService;
        this.messageSender = Objects.requireNonNull(messageSender);
    }

    public record Participant(long chatId, String name, String coupleId) {}

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage()) {
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
                        "Організатор може переглянути список через `/list` і запустити розіграш через `/run`.");
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
                    String inviteCode = organizerService.openRegistration(userId);
                    sendText(chatId, "✅ Реєстрацію відкрито.\nЗапрошувальний код: " + inviteCode);
                } catch (SecurityException exception) {
                    sendText(chatId, "❌ Лише організатор може відкрити реєстрацію.");
                } catch (IOException exception) {
                    logger.log(Level.SEVERE, "Could not read organizer state.", exception);
                    sendText(chatId, "❌ Не вдалося відкрити реєстрацію. Спробуй пізніше.");
                }
                break;

            case "/register":
                sendText(chatId,
                        "❌ Реєстрацію учасників ще не підключено. Дочекайся інструкцій організатора.");
                break;

            case "/list":
                if (!requireOrganizer(userId, chatId, privateChat)) {
                    break;
                }
                if (participants.isEmpty()) {
                    sendText(chatId, "Поки що ніхто не зареєструвався.");
                } else {
                    StringBuilder sb = new StringBuilder("📋 **Список учасників:**\n");
                    participants.values().forEach(p ->
                            sb.append("• ").append(p.name())
                                    .append(p.coupleId() != null ? " (пара: `" + p.coupleId() + "`)" : "")
                                    .append("\n")
                    );
                    sendText(chatId, sb.toString());
                }
                break;

            case "/run":
                if (requireOrganizer(userId, chatId, privateChat)) {
                    runSecretSanta(userId);
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

    private void runSecretSanta(long adminChatId) {
        List<Participant> list = new ArrayList<>(participants.values());
        if (list.size() < 2) {
            sendText(adminChatId, "❌ Замало учасників для розподілу (потрібно мінімум 2).");
            return;
        }

        try {
            Map<Participant, Participant> assignments = AssignmentGenerator.generate(list);

            // Розсилаємо результати кожному в особисті повідомлення
            for (Map.Entry<Participant, Participant> entry : assignments.entrySet()) {
                Participant giver = entry.getKey();
                Participant receiver = entry.getValue();

                String personalMessage = "🎁 **Жеребкування Таємного Санти завершено!** 🎅\n\n" +
                        "Твоя ціль — купити подарунок для: **" + receiver.name() + "** 🎉\n\n" +
                        "Зберігай інтригу і готуй сюрприз! 🤫";

                sendText(giver.chatId(), personalMessage);
            }

            sendText(adminChatId, "✅ Розподіл успішно завершено! Усі учасники отримали свої цілі в особисті чати.");
            participants.clear(); // Очищаємо список після розіграшу

        } catch (Exception e) {
            sendText(adminChatId, "❌ Не вдалося знайти комбінацію: " + e.getMessage() +
                    "\nСпробуйте ще раз або перевірте пари (можливо, пар занадто багато відносно кількості людей).");
        }
    }

    private void sendText(long chatId, String text) {
        try {
            messageSender.send(chatId, text);
        } catch (TelegramApiException exception) {
            logger.log(Level.SEVERE, "Failed to send Telegram response.", exception);
        }
    }

    private void sendTelegramMessage(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown");
        execute(message);
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
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        botsApi.registerBot(new SecretSantaBot(
                configuration.botToken(), configuration.botUsername(), organizerService));
        logger.info("🤖 Бот Таємного Санти успішно запущений і слухає повідомлення!");
    }
}