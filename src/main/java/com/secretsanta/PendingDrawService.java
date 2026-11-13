package com.secretsanta;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

final class PendingDrawService {
    enum Status {
        COMPLETED,
        PENDING,
        TOO_FEW_PARTICIPANTS
    }

    record Result(Status status, int failedDeliveries) {}

    private static final Logger LOGGER = Logger.getLogger(PendingDrawService.class.getName());

    private final PendingDrawStore store;
    private final ExchangeService exchangeService;
    private final OrganizerService organizerService;
    private final TelegramMessenger telegramMessenger;

    PendingDrawService(
            PendingDrawStore store,
            ExchangeService exchangeService,
            OrganizerService organizerService,
            TelegramMessenger telegramMessenger) {
        this.store = Objects.requireNonNull(store);
        this.exchangeService = Objects.requireNonNull(exchangeService);
        this.organizerService = Objects.requireNonNull(organizerService);
        this.telegramMessenger = Objects.requireNonNull(telegramMessenger);
        migrateLegacyDraw();
    }

    String openRegistration(long organizerId) throws IOException {
        return openRegistration(organizerId, "Organizer");
    }

    String openRegistration(long organizerId, String organizerName) throws IOException {
        if (!organizerService.isOrganizer(organizerId)) {
            throw new SecurityException("Only the organizer can open registration.");
        }
        try (PendingDrawStore.LockHandle ignored = store.acquireDrawLock()) {
            if (store.load().isPresent()) {
                throw new PendingDrawInProgressException();
            }
            try {
                return exchangeService.openRegistration(organizerId, organizerName);
            } catch (ExchangeService.ExchangeInProgressException exception) {
                throw new PendingDrawInProgressException();
            }
        }
    }

    boolean hasPendingDraw() throws IOException {
        try (PendingDrawStore.LockHandle ignored = store.acquireDrawLock()) {
            return store.load().isPresent() || exchangeService.hasActiveDraw();
        }
    }

    Result run() throws IOException {
        try (PendingDrawStore.LockHandle ignored = store.acquireDrawLock()) {
            ExchangeState state = exchangeService.state();
            if (state.phase() == ExchangeState.Phase.REGISTRATION_OPEN) {
                if (exchangeService.hasOpenPairRequests()) {
                    throw new IllegalStateException(
                            "Pair requests must be resolved before assignment.");
                }
                List<SecretSantaBot.Participant> participants = exchangeService.participants();
                if (participants.size() < 2) {
                    return new Result(Status.TOO_FEW_PARTICIPANTS, 0);
                }

                Map<SecretSantaBot.Participant, SecretSantaBot.Participant> assignments =
                        AssignmentGenerator.generate(participants);
                List<ExchangeState.Assignment> persistedAssignments = assignments.entrySet()
                        .stream()
                        .map(entry -> new ExchangeState.Assignment(
                                entry.getKey().chatId(), entry.getValue().chatId()))
                        .toList();
                exchangeService.beginDraw(persistedAssignments);
                state = exchangeService.state();
            } else if (state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS) {
                return new Result(Status.COMPLETED, 0);
            } else if (state.phase() != ExchangeState.Phase.DELIVERING) {
                if (state.participants().size() < 2) {
                    return new Result(Status.TOO_FEW_PARTICIPANTS, 0);
                }
                throw new IllegalStateException("Registration must be open before assignment.");
            }

            Map<Long, String> participantNames = new LinkedHashMap<>();
            state.participants().forEach(participant ->
                    participantNames.put(participant.userId(), participant.name()));
            int failedDeliveries = 0;
            for (ExchangeState.Assignment assignment : state.assignments()) {
                if (state.deliveredUserIds().contains(assignment.giverId())) {
                    continue;
                }

                try {
                    telegramMessenger.sendMessage(
                            assignment.giverId(),
                            assignmentMessage(participantNames.get(assignment.receiverId())),
                            BotButtons.assignmentReceived(state.drawGeneration()));
                } catch (TelegramApiException exception) {
                    failedDeliveries++;
                    LOGGER.log(
                            Level.WARNING,
                            "A Secret Santa assignment remains pending after Telegram delivery failed.",
                            exception);
                    continue;
                }

                exchangeService.markAssignmentDelivered(assignment.giverId());
                state = exchangeService.state();
            }

            if (state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS) {
                return new Result(Status.COMPLETED, 0);
            }
            return new Result(Status.PENDING, failedDeliveries);
        }
    }

    private void migrateLegacyDraw() {
        try (PendingDrawStore.LockHandle ignored = store.acquireDrawLock()) {
            PendingDrawStore.PendingDraw legacyDraw = store.load().orElse(null);
            if (legacyDraw != null) {
                exchangeService.migrateLegacyDraw(legacyDraw);
                store.delete();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not migrate the persisted pending draw.", exception);
        }
    }

    private static String assignmentMessage(String receiverName) {
        return "🎁 **Жеребкування Таємного Санти завершено!** 🎅\n\n"
                + "Твоя ціль — купити подарунок для: **"
                + escapeMarkdown(receiverName)
                + "** 🎉\n\n"
                + "Зберігай інтригу і готуй сюрприз! 🤫";
    }

    private static String escapeMarkdown(String value) {
        return value.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("*", "\\*")
                .replace("[", "\\[")
                .replace("`", "\\`");
    }

    static final class PendingDrawInProgressException extends IllegalStateException {
        private PendingDrawInProgressException() {
            super("A pending draw must be completed before opening another exchange.");
        }
    }
}
