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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

public final class OfflineBotSimulator {
    private static final String SETUP_CODE = "demo-setup";

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
        OrganizerService organizerService = new OrganizerService(
                new OrganizerStore(dataDirectory), SETUP_CODE);
        LocalMessenger messenger = new LocalMessenger(output);
        SecretSantaBot bot = new SecretSantaBot(
                "offline-token",
                "offline-bot",
                organizerService,
                messenger,
                new PendingDrawStore(dataDirectory, dataKey));

        output.println("Secret Santa offline simulator — no Telegram connection is used.");
        output.println("Use `as <user-id> <message>` to send a private message.");
        output.println("Use `click <number>` to choose an inline button as the current user.");
        output.println("Use `help` for examples or `quit` to exit.");

        long currentUserId = 100L;
        long nextCallbackId = 1L;
        String line;
        while (true) {
            output.print("[offline] > ");
            output.flush();
            line = input.readLine();
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
                case "as" -> {
                    if (parts.length < 3) {
                        output.println("Usage: as <user-id> <message>");
                        continue;
                    }
                    try {
                        long userId = Long.parseLong(parts[1]);
                        if (userId <= 0) {
                            output.println("User ID must be a positive number.");
                            continue;
                        }
                        currentUserId = userId;
                    } catch (NumberFormatException exception) {
                        output.println("User ID must be a positive number.");
                        continue;
                    }
                    bot.onUpdateReceived(privateMessage(currentUserId, parts[2]));
                }
                case "click" -> {
                    if (parts.length < 2) {
                        output.println("Usage: click <button-number>");
                        continue;
                    }
                    try {
                        int buttonNumber = Integer.parseInt(parts[1]);
                        String callbackData = messenger.callbackData(currentUserId, buttonNumber);
                        bot.onUpdateReceived(callback(currentUserId, nextCallbackId++, callbackData));
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
        output.println("  as 100 /claim demo-setup");
        output.println("  as 100 /open");
        output.println("  as 201 /join <invite-code> Alex");
        output.println("  as 202 /join <invite-code> Sam");
        output.println("  as 203 /join <invite-code> Jamie");
        output.println("  as 100 /list");
        output.println("  as 100 /pair");
        output.println("  click 1");
        output.println("  click 2");
        output.println("  as 100 /run");
        output.println("Pairing buttons are numbered in the latest message sent to that user.");
        output.println("Each user ID represents a separate simulated private chat.");
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
        private final Map<Long, List<String>> buttonsByUser = new HashMap<>();

        private LocalMessenger(PrintStream output) {
            this.output = output;
        }

        @Override
        public void sendMessage(long chatId, String text, InlineKeyboardMarkup keyboard) {
            output.println();
            output.println("[offline -> user " + chatId + "]");
            output.println(text);
            if (keyboard == null) {
                return;
            }

            List<String> callbackData = new ArrayList<>();
            int buttonNumber = 1;
            for (List<InlineKeyboardButton> row : keyboard.getKeyboard()) {
                for (InlineKeyboardButton button : row) {
                    output.println("  " + buttonNumber++ + ") " + button.getText());
                    callbackData.add(button.getCallbackData());
                }
            }
            buttonsByUser.put(chatId, callbackData);
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {
            output.println("[offline callback " + callbackQueryId + "] " + text);
        }

        private String callbackData(long userId, int buttonNumber) {
            List<String> callbackData = buttonsByUser.get(userId);
            if (callbackData == null || buttonNumber < 1 || buttonNumber > callbackData.size()) {
                throw new IllegalArgumentException(
                        "No button " + buttonNumber + " is available for user " + userId + ".");
            }
            return callbackData.get(buttonNumber - 1);
        }
    }
}
