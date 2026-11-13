package com.secretsanta;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

final class BotCommandController {
    private static final Logger LOGGER = Logger.getLogger(BotCommandController.class.getName());

    private final OrganizerService organizerService;
    private final ExchangeService exchangeService;
    private final PendingDrawService pendingDrawService;
    private final BotButtonController buttonController;
    private final BotResponder responder;
    private final Map<String, Consumer<Command>> commands;
    private final Map<ExchangeService.RegistrationResult, String> registrationMessages = Map.of(
            ExchangeService.RegistrationResult.REGISTERED, BotMessages.JOINED,
            ExchangeService.RegistrationResult.ALREADY_REGISTERED, BotMessages.ALREADY_REGISTERED,
            ExchangeService.RegistrationResult.INVALID_NAME, BotMessages.JOIN_NAME_RULES,
            ExchangeService.RegistrationResult.INVALID_INVITE, BotMessages.INVITE_INVALID_FINAL,
            ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                    BotMessages.REGISTRATION_CLOSED_FINAL);
    BotCommandController(
            OrganizerService organizerService,
            ExchangeService exchangeService,
            PendingDrawService pendingDrawService,
            BotButtonController buttonController,
            BotResponder responder) {
        this.organizerService = organizerService;
        this.exchangeService = exchangeService;
        this.pendingDrawService = pendingDrawService;
        this.buttonController = buttonController;
        this.responder = responder;
        commands = Map.of(
                "/start", this::showStart,
                "/claim", this::claim,
                "/open", this::open,
                "/join", this::join,
                "/register", this::showRegisterHelp,
                "/list", this::listParticipants,
                "/run", this::runDraw,
                "/pair", this::startPairing);
    }

    void handle(
            String messageText, long chatId, long userId, String firstName, boolean privateChat) {
        String[] parts = messageText.strip().split("\\s+", 2);
        String command = normalizeCommand(parts[0]);
        String arguments = parts.length > 1 ? parts[1].strip() : "";
        Command context = new Command(chatId, userId, firstName, arguments, privateChat);
        String action = switch (command) {
            case "/start" -> "command_start";
            case "/claim" -> "command_claim";
            case "/open" -> "command_open";
            case "/join" -> "command_join";
            case "/register" -> "command_register";
            case "/list" -> "command_list";
            case "/pair" -> "command_pair";
            default -> null;
        };
        if (action != null) {
            LOGGER.info(BotLogMessages.ACTION_ATTEMPTED + action);
        }
        commands.getOrDefault(command, this::showUnknownCommand).accept(context);
    }

    private void showStart(Command command) {
        buttonController.showStartMenu(
                command.chatId(), command.userId(), command.privateChat());
    }

    private void claim(Command command) {
        if (!requirePrivate(command, BotMessages.COMMAND_PRIVATE_ONLY)) {
            return;
        }
        if (command.arguments().isBlank()) {
            responder.sendText(command.chatId(), BotMessages.CLAIM_USAGE);
            return;
        }
        try {
            OrganizerService.ClaimResult result =
                    organizerService.claim(command.userId(), command.arguments());
            if (result == OrganizerService.ClaimResult.CLAIMED) {
                buttonController.refreshCommandMenu();
            }
            responder.sendText(
                    command.chatId(),
                    result == OrganizerService.ClaimResult.CLAIMED
                            ? BotMessages.CLAIM_SUCCESS
                            : BotMessages.CLAIM_COMMAND_FAILED);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ORGANIZER_CLAIM_PERSIST_FAILED, exception);
            responder.sendText(command.chatId(), BotMessages.CLAIM_PERSIST_FAILED);
        }
    }

    private void open(Command command) {
        if (!requirePrivate(command, BotMessages.COMMAND_PRIVATE_ONLY)) {
            return;
        }
        if (!command.arguments().isBlank()) {
            responder.sendText(command.chatId(), BotMessages.OPEN_USAGE);
            return;
        }
        try {
            String inviteCode =
                    buttonController.openRegistration(command.userId(), command.firstName());
            responder.sendText(command.chatId(), BotMessages.registrationOpened(inviteCode));
        } catch (PendingDrawService.PendingDrawInProgressException exception) {
            responder.sendText(command.chatId(), BotMessages.OPEN_PENDING_DRAW);
        } catch (IllegalArgumentException exception) {
            responder.sendText(command.chatId(), BotMessages.PROFILE_NAME_INVALID);
        } catch (SecurityException exception) {
            responder.sendText(command.chatId(), BotMessages.OPEN_ORGANIZER_ONLY);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ORGANIZER_STATE_READ_FAILED, exception);
            responder.sendText(command.chatId(), BotMessages.REGISTRATION_OPEN_FAILED);
        }
    }

    private void join(Command command) {
        if (!requirePrivate(command, BotMessages.JOIN_PRIVATE_ONLY)) {
            return;
        }
        String[] arguments = command.arguments().split("\\s+", 2);
        if (arguments.length < 2) {
            responder.sendText(command.chatId(), BotMessages.JOIN_USAGE);
            return;
        }
        try {
            ExchangeService.RegistrationResult result = exchangeService.register(
                    command.userId(), arguments[0], arguments[1]);
            if (result == ExchangeService.RegistrationResult.REGISTERED) {
                buttonController.notifyOrganizerOfJoin(command.userId());
            }
            responder.sendText(command.chatId(), registrationMessages.get(result));
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.REGISTRATION_PERSIST_FAILED, exception);
            responder.sendText(command.chatId(), BotMessages.REGISTRATION_FAILED);
        }
    }

    private void showRegisterHelp(Command command) {
        responder.sendText(command.chatId(), BotMessages.REGISTER_HELP);
    }

    private void listParticipants(Command command) {
        if (!requireOrganizer(command.userId(), command.chatId(), command.privateChat())) {
            return;
        }
        List<SecretSantaBot.Participant> roster = exchangeService.participants();
        if (roster.isEmpty()) {
            responder.sendText(command.chatId(), BotMessages.EMPTY_ROSTER);
            return;
        }

        responder.sendText(
                command.chatId(),
                BotMessages.participantRoster(roster, exchangeService.state().pairRequests()));
    }

    private void runDraw(Command command) {
        if (!requireOrganizer(command.userId(), command.chatId(), command.privateChat())) {
            return;
        }
        buttonController.cancelPairing();
        try {
            PendingDrawService.Result result = pendingDrawService.run();
            responder.sendText(command.userId(), BotMessages.drawResult(result));
        } catch (IllegalStateException exception) {
            responder.sendText(command.userId(), BotMessages.RUN_NO_ASSIGNMENT);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.DRAW_RESUME_FAILED, exception);
            responder.sendText(command.userId(), BotMessages.RUN_FAILED);
        }
    }

    private void startPairing(Command command) {
        if (!requireOrganizer(command.userId(), command.chatId(), command.privateChat())) {
            return;
        }
        if (!command.arguments().isBlank()) {
            responder.sendText(command.chatId(), BotMessages.PAIR_USAGE);
            return;
        }
        try {
            if (pendingDrawService.hasPendingDraw()) {
                buttonController.cancelPairing();
                responder.sendText(command.chatId(), BotMessages.PAIR_PENDING_DRAW);
                return;
            }
            buttonController.startPairing(command.userId());
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.PENDING_DRAW_CHECK_FAILED, exception);
            responder.sendText(command.chatId(), BotMessages.PAIR_PENDING_DRAW_CHECK_FAILED);
        }
    }

    private boolean requireOrganizer(long userId, long chatId, boolean privateChat) {
        if (!privateChat) {
            responder.sendText(chatId, BotMessages.ORGANIZER_COMMAND_PRIVATE_ONLY);
            return false;
        }
        try {
            if (organizerService.isOrganizer(userId)) {
                return true;
            }
            responder.sendText(chatId, BotMessages.ORGANIZER_COMMAND_ONLY);
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, BotLogMessages.ORGANIZER_STATE_READ_FAILED, exception);
            responder.sendText(chatId, BotMessages.ORGANIZER_CHECK_COMMAND_FAILED);
        }
        return false;
    }

    private boolean requirePrivate(Command command, String failureMessage) {
        if (command.privateChat()) {
            return true;
        }
        responder.sendText(command.chatId(), failureMessage);
        return false;
    }

    private void showUnknownCommand(Command command) {
        responder.sendText(command.chatId(), BotMessages.NO_COMMAND);
    }

    private static String normalizeCommand(String command) {
        String normalized = command.toLowerCase(java.util.Locale.ROOT);
        int botNameSeparator = normalized.indexOf('@');
        return botNameSeparator < 0 ? normalized : normalized.substring(0, botNameSeparator);
    }

    private record Command(
            long chatId, long userId, String firstName, String arguments, boolean privateChat) {}
}
