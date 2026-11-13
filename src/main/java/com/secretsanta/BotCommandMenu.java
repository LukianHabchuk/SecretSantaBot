package com.secretsanta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScope;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeChat;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

final class BotCommandMenu {
    private static final Logger LOGGER = Logger.getLogger(BotCommandMenu.class.getName());
    private static final List<BotCommand> PARTICIPANT_COMMANDS = List.of(
            new BotCommand("start", "Відкрити меню"),
            new BotCommand("join", "Приєднатися за кодом та ім'ям"),
            new BotCommand("register", "Як приєднатися до обміну"));
    private static final List<BotCommand> ORGANIZER_COMMANDS = List.of(
            new BotCommand("start", "Відкрити меню"),
            new BotCommand("open", "Відкрити реєстрацію"),
            new BotCommand("list", "Показати список учасників"),
            new BotCommand("pair", "Керувати парами"),
            new BotCommand("run", "Провести або повторити жеребкування"));

    private final OrganizerService organizerService;
    private final TelegramMessenger messenger;
    private final LanguageStore languageStore;
    private boolean synchronizedWithTelegram;

    BotCommandMenu(OrganizerService organizerService, TelegramMessenger messenger) {
        this(organizerService, messenger, null);
    }

    BotCommandMenu(
            OrganizerService organizerService,
            TelegramMessenger messenger,
            LanguageStore languageStore) {
        this.organizerService = organizerService;
        this.messenger = messenger;
        this.languageStore = languageStore;
    }

    synchronized void ensureInitialized() {
        if (!synchronizedWithTelegram) {
            refresh();
        }
    }

    synchronized void refresh() {
        synchronizedWithTelegram = false;
        try {
            OptionalLong organizerId = organizerService.organizerId();
            List<BotCommand> participantCommands = new ArrayList<>(PARTICIPANT_COMMANDS);
            if (organizerId.isEmpty()) {
                participantCommands.add(new BotCommand("claim", "Підтвердити права організатора"));
            }
            if (languageStore != null) {
                participantCommands = localizedCommands(false, organizerId.isEmpty(), BotLanguage.UKRAINIAN);
            }
            publish(participantCommands, new BotCommandScopeAllPrivateChats());
            if (organizerId.isPresent()) {
                List<BotCommand> organizerCommands = languageStore == null ? ORGANIZER_COMMANDS
                        : localizedCommands(true, false,
                                languageStore.get(organizerId.getAsLong()).orElse(BotLanguage.UKRAINIAN));
                publish(organizerCommands, new BotCommandScopeChat(
                        Long.toString(organizerId.getAsLong())));
            }
            synchronizedWithTelegram = true;
        } catch (IOException | TelegramApiException exception) {
            LOGGER.log(Level.WARNING, BotLogMessages.COMMAND_MENU_UPDATE_FAILED
                    + " (" + exception.getClass().getSimpleName() + ")");
        }
    }

    synchronized void refreshForUser(long userId) {
        if (languageStore == null) {
            return;
        }
        try {
            OptionalLong organizerId = organizerService.organizerId();
            boolean organizer = organizerId.isPresent() && organizerId.getAsLong() == userId;
            BotLanguage language = languageStore.get(userId).orElse(BotLanguage.UKRAINIAN);
            publish(localizedCommands(organizer, organizerId.isEmpty(), language),
                    new BotCommandScopeChat(Long.toString(userId)));
        } catch (IOException | TelegramApiException exception) {
            LOGGER.log(Level.WARNING, BotLogMessages.COMMAND_MENU_UPDATE_FAILED
                    + " (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private static List<BotCommand> localizedCommands(
            boolean organizer, boolean claimAvailable, BotLanguage language) {
        List<BotCommand> commands = new ArrayList<>();
        for (BotCommand command : organizer ? ORGANIZER_COMMANDS : PARTICIPANT_COMMANDS) {
            String description = language == BotLanguage.ENGLISH
                    ? englishDescription(command.getCommand()) : command.getDescription();
            commands.add(new BotCommand(command.getCommand(), description));
        }
        if (!organizer && claimAvailable) {
            commands.add(new BotCommand("claim", language == BotLanguage.ENGLISH
                    ? "Claim organizer access" : "Підтвердити права організатора"));
        }
        commands.add(new BotCommand("language", language == BotLanguage.ENGLISH
                ? "Change language" : "Змінити мову"));
        commands.add(new BotCommand("forgetlanguage", language == BotLanguage.ENGLISH
                ? "Delete language preference" : "Видалити вибір мови"));
        return commands;
    }

    private static String englishDescription(String command) {
        return switch (command) {
            case "start" -> "Open menu";
            case "join" -> "Join with a code and name";
            case "register" -> "How to join the exchange";
            case "open" -> "Open registration";
            case "list" -> "Show participants";
            case "pair" -> "Manage pairs";
            case "run" -> "Run or retry the draw";
            default -> throw new IllegalArgumentException("Unknown command menu entry.");
        };
    }

    private void publish(List<BotCommand> commands, BotCommandScope scope)
            throws TelegramApiException {
        if (!messenger.setMyCommands(new SetMyCommands(commands, scope, null))) {
            throw new TelegramApiException("Telegram rejected the command menu update.");
        }
    }
}
