package com.secretsanta;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class SecretSantaBot extends TelegramLongPollingBot {

    static Logger logger = Logger.getLogger(SecretSantaBot.class.getName());

    private final String botToken;
    private final String botUsername;

    // Зберігаємо учасників у пам'яті: chatId -> Participant
    private final Map<Long, Participant> participants = new ConcurrentHashMap<>();

    public SecretSantaBot(String botToken, String botUsername) {
        this.botToken = botToken;
        this.botUsername = botUsername;
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
        if (!update.hasMessage() || !update.hasMessage()) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        String messageText = update.getMessage().getText().trim();
        String[] parts = messageText.split("\\s+");
        String command = parts[0].toLowerCase();

        // Обрізаємо @botname, якщо команда надіслана в груповому чаті
        if (command.contains("@")) {
            command = command.split("@")[0];
        }

        switch (command) {
            case "/start":
                sendText(chatId, "🎅 **Привіт! Це твій персональний бот для Таємного Санти.**\n\n" +
                        "Кожен учасник має написати мені в особисті повідомлення команду для реєстрації:\n" +
                        "`/register ТвоєІм'я [ID_пари]`\n\n" +
                        "📌 **Приклади:**\n" +
                        "• Без пари: `/register Олексій`\n" +
                        "• З парою (вкажіть однаковий ID для обох): `/register Марія couple1` та `/register Іван couple1`\n\n" +
                        "📋 **Інші команди:**\n" +
                        "/list — Переглянути список зареєстрованих\n" +
                        "/run — Зробити розіграш (коли всі додалися)");
                break;

            case "/register":
                if (parts.length < 2) {
                    sendText(chatId, "❌ Будь ласка, вкажи своє ім'я.\nПриклад: `/register Олексій` або `/register Марія couple1`");
                    return;
                }
                String name = parts[1];
                String coupleId = parts.length > 2 ? parts[2] : null;

                participants.put(chatId, new Participant(chatId, name, coupleId));

                String reply = "✅ Успішно зареєстровано як **" + name + "**";
                if (coupleId != null) {
                    reply += " у парі (`" + coupleId + "`)!";
                } else {
                    reply += " (без пари).";
                }
                sendText(chatId, reply);
                break;

            case "/list":
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
                runSecretSanta(chatId);
                break;

            default:
                sendText(chatId, "Невідома команда. Напиши /start для інструкції.");
        }
    }

    private void runSecretSanta(long adminChatId) {
        List<Participant> list = new ArrayList<>(participants.values());
        if (list.size() < 2) {
            sendText(adminChatId, "❌ Замало учасників для розподілу (потрібно мінімум 2).");
            return;
        }

        try {
            Map<Participant, Participant> assignments = generateAssignments(list);

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

    // Алгоритм розподілу з перевіркою пар і самоповторів
    private Map<Participant, Participant> generateAssignments(List<Participant> group) {
        List<Participant> givers = new ArrayList<>(group);
        List<Participant> receivers = new ArrayList<>(group);

        Random random = new Random();
        boolean valid = false;
        int attempts = 0;
        int maxAttempts = 15000;

        while (!valid && attempts < maxAttempts) {
            Collections.shuffle(receivers, random);
            valid = true;
            attempts++;

            for (int i = 0; i < givers.size(); i++) {
                Participant giver = givers.get(i);
                Participant receiver = receivers.get(i);

                // Умови: не можна дарувати собі або своїй половинці
                if (giver.equals(receiver) ||
                        (giver.coupleId() != null && giver.coupleId().equals(receiver.coupleId()))) {
                    valid = false;
                    break;
                }
            }
        }

        if (!valid) {
            throw new IllegalStateException("Вичерпано ліміт спроб. Змініть конфігурацію пар.");
        }

        Map<Participant, Participant> assignments = new LinkedHashMap<>();
        for (int i = 0; i < givers.size(); i++) {
            assignments.put(givers.get(i), receivers.get(i));
        }

        return assignments;
    }

    private void sendText(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        message.setParseMode("Markdown");
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            // Вставте сюди токен вашого бота від @BotFather та username
            botsApi.registerBot(new SecretSantaBot("YOUR_BOT_TOKEN", "YOUR_BOT_USERNAME"));
            logger.info("🤖 Бот Таємного Санти успішно запущений і слухає повідомлення!");
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}