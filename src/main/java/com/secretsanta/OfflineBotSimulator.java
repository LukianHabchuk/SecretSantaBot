package com.secretsanta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public final class OfflineBotSimulator {
    static final String DEMO_SETUP_CODE = "offline-simulator-setup-code-for-local-testing";

    private OfflineBotSimulator() {}

    public static void main(String[] args) throws IOException {
        Path dataDirectory = Files.createTempDirectory("secret-santa-offline-");
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);

        try {
            runSession(
                    new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                    System.out,
                    dataDirectory,
                    dataKey);
        } finally {
            deleteTemporaryDirectory(dataDirectory);
        }
    }

    static void runSession(BufferedReader input, PrintStream output, Path dataDirectory)
            throws IOException {
        runSession(input, output, dataDirectory, new byte[32]);
    }

    private static void runSession(
            BufferedReader input, PrintStream output, Path dataDirectory, byte[] dataKey)
            throws IOException {
        Session session = createSession(dataDirectory, dataKey, output);

        output.println("Secret Santa offline simulator — no Telegram connection is used.");
        output.println("Use `as <user-id> <message>` to send a private message.");
        output.println("Use `click <number>` for the latest keyboard, or `click <keyboard> <number>` for an earlier one.");
        output.println("Use `fail <user-id>` and `recover <user-id>` to simulate delivery outages.");
        output.println("Use `help` for examples or `quit` to exit.");

        long currentUserId = 100L;
        long nextCallbackId = 1L;
        while (true) {
            output.print("[offline] > ");
            output.flush();
            String line = input.readLine();
            if (line == null) {
                return;
            }
            String[] parts = line.strip().split("\\s+", 3);
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }

            switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "quit", "exit" -> {
                    return;
                }
                case "help" -> printHelp(output);
                case "fail", "recover" -> {
                    if (parts.length < 2) {
                        output.println("Usage: " + parts[0] + " <user-id>");
                        continue;
                    }
                    try {
                        long userId = positiveUserId(parts[1]);
                        if ("fail".equalsIgnoreCase(parts[0])) {
                            session.failDeliveryTo(userId);
                            output.println("Simulated delivery failure for user " + userId + ".");
                        } else {
                            session.recoverDeliveryTo(userId);
                            output.println("Simulated delivery restored for user " + userId + ".");
                        }
                    } catch (NumberFormatException exception) {
                        output.println("User ID must be a positive number.");
                    }
                }
                case "as" -> {
                    if (parts.length < 3) {
                        output.println("Usage: as <user-id> <message>");
                        continue;
                    }
                    try {
                        currentUserId = positiveUserId(parts[1]);
                    } catch (NumberFormatException exception) {
                        output.println("User ID must be a positive number.");
                        continue;
                    }
                    session.sendAs(currentUserId, parts[2]);
                }
                case "click" -> {
                    if (parts.length < 2 || parts.length > 3) {
                        output.println("Usage: click <button-number> | click <keyboard-number> <button-number>");
                        continue;
                    }
                    try {
                        if (parts.length == 2) {
                            session.click(currentUserId, Integer.parseInt(parts[1]));
                        } else {
                            session.click(
                                    currentUserId,
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]));
                        }
                    } catch (IllegalArgumentException exception) {
                        output.println(exception.getMessage());
                    }
                }
                default -> output.println("Unknown simulator command. Type `help` for examples.");
            }
        }
    }

    private static void printHelp(PrintStream output) {
        output.println("Examples (use the same organizer ID for organizer commands):");
        output.println("  as 100 /start");
        output.println("  as 100 /claim " + DEMO_SETUP_CODE);
        output.println("  as 100 /open");
        output.println("  as 201 /join <invite-code> Alex");
        output.println("  as 202 /join <invite-code> Sam");
        output.println("  as 203 /join <invite-code> Jamie");
        output.println("  as 100 /list");
        output.println("  as 100 /pair");
        output.println("  click 1");
        output.println("  click 2  (choose a partner or accept a request)");
        output.println("  fail 203  (simulate a participant's delivery failure)");
        output.println("  as 100 /run");
        output.println("  recover 203");
        output.println("  as 100 /start, then click the current draw's retry button");
        output.println("Pair requests are accepted by their recipient in that user's private chat.");
        output.println("Use /start to view buttons; earlier assignment keyboards remain clickable by keyboard number.");
        output.println("Each user ID represents a separate simulated private chat.");
    }

    static Session createSession(Path dataDirectory, byte[] dataKey, PrintStream output)
            throws IOException {
        OrganizerService organizerService = new OrganizerService(
                new OrganizerStore(dataDirectory), DEMO_SETUP_CODE);
        LocalMessenger messenger = new LocalMessenger(output);
        SecretSantaBot bot = new SecretSantaBot(
                "offline-token",
                "offline-bot",
                organizerService,
                messenger,
                new PendingDrawStore(dataDirectory, dataKey),
                new LanguageStore(dataDirectory, dataKey));
        return new Session(bot, messenger);
    }

    private static long positiveUserId(String value) {
        long userId = Long.parseLong(value);
        if (userId <= 0) {
            throw new NumberFormatException("User ID must be positive.");
        }
        return userId;
    }

    static final class Session {
        private final SecretSantaBot bot;
        private final LocalMessenger messenger;
        private long nextCallbackId = 1L;

        private Session(SecretSantaBot bot, LocalMessenger messenger) {
            this.bot = bot;
            this.messenger = messenger;
        }

        void sendAs(long userId, String message) {
            bot.onUpdateReceived(privateMessage(userId, message));
        }

        void click(long userId, int buttonNumber) {
            String callbackData = messenger.callbackData(userId, buttonNumber);
            bot.onUpdateReceived(callback(userId, nextCallbackId++, callbackData));
        }

        void click(long userId, int keyboardNumber, int buttonNumber) {
            String callbackData = messenger.callbackData(userId, keyboardNumber, buttonNumber);
            bot.onUpdateReceived(callback(userId, nextCallbackId++, callbackData));
        }

        void clickButton(long userId, String buttonText) {
            String callbackData = messenger.callbackData(userId, buttonText);
            bot.onUpdateReceived(callback(userId, nextCallbackId++, callbackData));
        }

        void failDeliveryTo(long userId) {
            messenger.failDeliveryTo(userId);
        }

        void recoverDeliveryTo(long userId) {
            messenger.recoverDeliveryTo(userId);
        }

        String latestMessageContaining(long userId, String text) {
            return messenger.latestMessageContaining(userId, text);
        }
    }

    private static Update privateMessage(long userId, String text) {
        User user = new User();
        user.setId(userId);
        user.setFirstName("User " + userId);

        Chat chat = new Chat();
        chat.setId(userId);
        chat.setType("private");

        Message message = new Message();
        message.setFrom(user);
        message.setChat(chat);
        message.setText(text);

        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Update callback(long userId, long callbackId, String data) {
        User user = new User();
        user.setId(userId);
        user.setFirstName("User " + userId);

        Chat chat = new Chat();
        chat.setId(userId);
        chat.setType("private");

        Message message = new Message();
        message.setChat(chat);

        CallbackQuery callback = new CallbackQuery();
        callback.setId("offline-" + callbackId);
        callback.setFrom(user);
        callback.setMessage(message);
        callback.setData(data);

        Update update = new Update();
        update.setCallbackQuery(callback);
        return update;
    }

    private static void deleteTemporaryDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
            }
        }
    }

    private static final class LocalMessenger implements TelegramMessenger {
        private final PrintStream output;
        private final Map<Long, List<List<InlineKeyboardButton>>> keyboardsByUser =
                new HashMap<>();
        private final Map<Long, List<String>> messagesByUser = new HashMap<>();
        private final Set<Long> failedDeliveryUserIds = new HashSet<>();

        private LocalMessenger(PrintStream output) {
            this.output = output;
        }

        @Override
        public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard)
                throws TelegramApiException {
            if (failedDeliveryUserIds.contains(chatId)) {
                throw new TelegramApiException("Simulated delivery failure.");
            }
            output.println();
            output.println("[offline -> user " + chatId + "]");
            output.println(text);
            messagesByUser.computeIfAbsent(chatId, ignored -> new ArrayList<>()).add(text);
            if (keyboard == null) {
                return;
            }

            List<List<InlineKeyboardButton>> userKeyboards =
                    keyboardsByUser.computeIfAbsent(chatId, ignored -> new ArrayList<>());
            output.println("[keyboard " + (userKeyboards.size() + 1) + " for user " + chatId + "]");
            List<InlineKeyboardButton> buttons = new ArrayList<>();
            int buttonNumber = 1;
            for (List<InlineKeyboardButton> row : keyboard.getKeyboard()) {
                for (InlineKeyboardButton button : row) {
                    output.println("  " + buttonNumber++ + ") " + button.getText());
                    buttons.add(button);
                }
            }
            userKeyboards.add(List.copyOf(buttons));
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {
            output.println("[offline callback " + callbackQueryId + "] " + text);
        }

        @Override
        public boolean setMyCommands(SetMyCommands commands) {
            return true;
        }

        private String callbackData(long userId, int buttonNumber) {
            List<List<InlineKeyboardButton>> userKeyboards = keyboardsByUser.get(userId);
            if (userKeyboards == null || userKeyboards.isEmpty()) {
                throw new IllegalArgumentException(
                        "No buttons are available for user " + userId + ".");
            }
            return callbackData(userId, userKeyboards.size(), buttonNumber);
        }

        private String callbackData(long userId, int keyboardNumber, int buttonNumber) {
            List<List<InlineKeyboardButton>> userKeyboards = keyboardsByUser.get(userId);
            if (userKeyboards == null
                    || keyboardNumber < 1
                    || keyboardNumber > userKeyboards.size()) {
                throw new IllegalArgumentException(
                        "No keyboard " + keyboardNumber + " is available for user " + userId + ".");
            }
            List<InlineKeyboardButton> buttons = userKeyboards.get(keyboardNumber - 1);
            if (buttonNumber < 1 || buttonNumber > buttons.size()) {
                throw new IllegalArgumentException(
                        "No button " + buttonNumber + " is available for user " + userId + ".");
            }
            return buttons.get(buttonNumber - 1).getCallbackData();
        }

        private String callbackData(long userId, String buttonText) {
            List<List<InlineKeyboardButton>> userKeyboards = keyboardsByUser.get(userId);
            if (userKeyboards == null || userKeyboards.isEmpty()) {
                throw new IllegalArgumentException("No buttons are available for user " + userId + ".");
            }
            return userKeyboards.reversed().stream()
                    .flatMap(List::stream)
                    .filter(button -> button.getText().equals(buttonText))
                    .map(InlineKeyboardButton::getCallbackData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No button named \"" + buttonText + "\" is available for user "
                                    + userId + ". Available buttons: "
                                    + userKeyboards.stream()
                                            .flatMap(List::stream)
                                            .map(InlineKeyboardButton::getText)
                                            .toList()));
        }

        private String latestMessageContaining(long userId, String text) {
            List<String> messages = messagesByUser.get(userId);
            if (messages == null) {
                throw new IllegalArgumentException("No message is available for user " + userId + ".");
            }
            return messages.reversed().stream()
                    .filter(message -> message.contains(text))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No message containing \"" + text + "\" is available for user "
                                    + userId + "."));
        }

        private void failDeliveryTo(long userId) {
            failedDeliveryUserIds.add(userId);
        }

        private void recoverDeliveryTo(long userId) {
            failedDeliveryUserIds.remove(userId);
        }
    }
}
