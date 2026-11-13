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
    private boolean synchronizedWithTelegram;

    BotCommandMenu(OrganizerService organizerService, TelegramMessenger messenger) {
        this.organizerService = organizerService;
        this.messenger = messenger;
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
            publish(participantCommands, new BotCommandScopeAllPrivateChats());
            if (organizerId.isPresent()) {
                publish(ORGANIZER_COMMANDS, new BotCommandScopeChat(
                        Long.toString(organizerId.getAsLong())));
            }
            synchronizedWithTelegram = true;
        } catch (IOException | TelegramApiException exception) {
            LOGGER.log(Level.WARNING, BotLogMessages.COMMAND_MENU_UPDATE_FAILED
                    + " (" + exception.getClass().getSimpleName() + ")");
        }
    }

    private void publish(List<BotCommand> commands, BotCommandScope scope)
            throws TelegramApiException {
        if (!messenger.setMyCommands(new SetMyCommands(commands, scope, null))) {
            throw new TelegramApiException("Telegram rejected the command menu update.");
        }
    }
}
