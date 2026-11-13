package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

class PendingDrawServiceTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void logsCompletionOnlyAfterAllDeliveriesAndOnlyOnce() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        RecordingMessenger messenger = new RecordingMessenger(202L);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY), exchangeService, organizerService, messenger);
        List<String> infoMessages = new ArrayList<>();
        Logger logger = Logger.getLogger(PendingDrawService.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel() == java.util.logging.Level.INFO) {
                    infoMessages.add(record.getMessage());
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(handler);
        try {
            assertEquals(PendingDrawService.Status.PENDING, service.run().status());
            assertTrue(infoMessages.isEmpty());
            messenger.failChatId = null;
            assertEquals(PendingDrawService.Status.COMPLETED, service.run().status());
            assertEquals(PendingDrawService.Status.COMPLETED, service.run().status());
            assertEquals(List.of("assignment_delivery_completed"), infoMessages);
        } finally {
            logger.removeHandler(handler);
        }
    }

    @Test
    void retriesThePersistedDrawAfterRestartWithoutRedrawing() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger firstMessenger = new RecordingMessenger(202L);
        PendingDrawService firstService =
                new PendingDrawService(store, exchangeService, organizerService, firstMessenger);

        PendingDrawService.Result firstResult = firstService.run();
        ExchangeState persistedDraw = exchangeService.state();

        assertEquals(PendingDrawService.Status.PENDING, firstResult.status());
        assertEquals(1, firstResult.failedDeliveries());
        assertEquals(ExchangeState.Phase.DELIVERING, persistedDraw.phase());
        assertEquals(3, persistedDraw.deliveredUserIds().size());
        assertFalse(organizerService.isRegistrationOpen());

        OrganizerService restartedOrganizerService = createOrganizerService();
        ExchangeService restartedExchangeService =
                createExchangeService(restartedOrganizerService);
        RecordingMessenger retryMessenger = new RecordingMessenger(null);
        PendingDrawService restartedService = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                restartedExchangeService,
                restartedOrganizerService,
                retryMessenger);

        PendingDrawService.Result retryResult = restartedService.run();

        assertEquals(PendingDrawService.Status.COMPLETED, retryResult.status());
        assertEquals(0, retryResult.failedDeliveries());
        assertEquals(1, retryMessenger.messages().size());
        ExchangeState.Assignment failedAssignment = persistedDraw.assignments().stream()
                .filter(assignment -> assignment.giverId() == 202L)
                .findFirst()
                .orElseThrow();
        String receiverName = restartedExchangeService
                .participant(failedAssignment.receiverId())
                .orElseThrow()
                .name();
        assertTrue(retryMessenger.messages().getFirst().text().contains(receiverName));
        assertEquals(
                firstMessenger.messages().stream()
                        .filter(message -> message.chatId() == 202L)
                        .findFirst()
                        .orElseThrow()
                        .text(),
                retryMessenger.messages().getFirst().text());
        assertEquals(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                restartedExchangeService.state().phase());
        assertEquals(persistedDraw.assignments(), restartedExchangeService.state().assignments());
        assertEquals(4, restartedExchangeService.participants().size());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void retryInTheSameProcessRetainsRosterAfterAllDeliveriesSucceed() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger messenger = new RecordingMessenger(202L);
        PendingDrawService service =
                new PendingDrawService(store, exchangeService, organizerService, messenger);

        assertEquals(PendingDrawService.Status.PENDING, service.run().status());
        messenger.failChatId = null;
        PendingDrawService.Result retryResult = service.run();

        assertEquals(PendingDrawService.Status.COMPLETED, retryResult.status());
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, exchangeService.state().phase());
        assertEquals(4, exchangeService.participants().size());
        assertTrue(store.load().isEmpty());
        assertEquals(
                2,
                messenger.messages().stream()
                        .filter(message -> message.chatId() == 202L)
                        .count());
    }

    @Test
    void wrongKeyPreventsLoadingThePersistedDrawWithoutSendingAssignments() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        PendingDrawStore originalStore = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger initialMessenger = new RecordingMessenger(202L);
        new PendingDrawService(
                        originalStore, exchangeService, organizerService, initialMessenger)
                .run();

        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        RecordingMessenger retryMessenger = new RecordingMessenger(null);

        assertThrows(
                IllegalStateException.class,
                () -> new PendingDrawService(
                        new PendingDrawStore(dataDirectory, wrongKey),
                        new ExchangeService(
                                createOrganizerService(),
                                new ExchangeStore(dataDirectory, wrongKey)),
                        createOrganizerService(),
                        retryMessenger));
        assertTrue(retryMessenger.messages().isEmpty());
        assertTrue(originalStore.load().isEmpty());
        assertEquals(
                ExchangeState.Phase.DELIVERING,
                originalStore.exchangeStore().load().orElseThrow().phase());
    }

    @Test
    void impossibleDrawSendsNothingAndLeavesRosterAvailable() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeService(organizerService);
        String inviteCode = exchangeService.openRegistration(100L);
        exchangeService.register(201L, inviteCode, "Guest 201");
        exchangeService.register(202L, inviteCode, "Guest 202");
        exchangeService.pair(201L, 202L);
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service =
                new PendingDrawService(store, exchangeService, organizerService, messenger);

        assertThrows(IllegalStateException.class, service::run);

        assertTrue(messenger.messages().isEmpty());
        assertEquals(3, exchangeService.participants().size());
        assertEquals(ExchangeState.Phase.REGISTRATION_OPEN, exchangeService.state().phase());
        assertTrue(organizerService.isRegistrationOpen());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void unresolvedPairRequestBlocksAssignmentWithoutClosingRegistration() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        exchangeService.requestPair(201L, 202L);
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service =
                new PendingDrawService(store, exchangeService, organizerService, messenger);

        assertThrows(IllegalStateException.class, service::run);

        assertTrue(messenger.messages().isEmpty());
        assertTrue(store.load().isEmpty());
        assertEquals(ExchangeState.Phase.REGISTRATION_OPEN, exchangeService.state().phase());
        assertEquals(1, exchangeService.state().pairRequests().size());
    }

    @Test
    void persistsCompleteDrawBeforeSendingAnyAssignment() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeStore exchangeStore = new ExchangeStore(dataDirectory, KEY);
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        boolean[] persistedBeforeSend = {false};
        TelegramMessenger messenger = new TelegramMessenger() {
            @Override
            public void sendMessage(
                    long chatId, String text, InlineKeyboardMarkup keyboard) throws TelegramApiException {
                try {
                    ExchangeState state = exchangeStore.load().orElseThrow();
                    assertEquals(ExchangeState.Phase.DELIVERING, state.phase());
                    assertEquals(4, state.assignments().size());
                    if (!persistedBeforeSend[0]) {
                        assertTrue(state.deliveredUserIds().isEmpty());
                    }
                    persistedBeforeSend[0] = true;
                } catch (IOException exception) {
                    throw new TelegramApiException(exception);
                }
            }

            @Override
            public void answerCallback(String callbackQueryId, String text) {}
        };
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                messenger);

        service.run();

        assertTrue(persistedBeforeSend[0]);
    }

    @Test
    void privatelyDeliversOnlyEachParticipantsOwnAssignment() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                messenger);

        assertEquals(PendingDrawService.Status.COMPLETED, service.run().status());

        ExchangeState state = exchangeService.state();
        assertEquals(4, messenger.messages().size());
        assertTrue(messenger.messages().stream().anyMatch(message -> message.chatId() == 100L));
        for (ExchangeState.Assignment assignment : state.assignments()) {
            SentMessage deliveredMessage = messenger.messages().stream()
                    .filter(message -> message.chatId() == assignment.giverId())
                    .findFirst()
                    .orElseThrow();
            String receiverName = exchangeService
                    .participant(assignment.receiverId())
                    .orElseThrow()
                    .name();
            assertTrue(deliveredMessage.text().contains(receiverName));
            state.participants().stream()
                    .filter(participant -> participant.userId() != assignment.receiverId())
                    .forEach(participant ->
                            assertFalse(deliveredMessage.text().contains(participant.name())));
        }
    }

    @Test
    void replacementDrawIncrementsGenerationAndClearsPreviousConfirmations() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        exchangeService.pair(201L, 202L);
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                messenger);

        assertEquals(PendingDrawService.Status.COMPLETED, service.run().status());
        ExchangeState originalDraw = exchangeService.state();
        for (ExchangeState.Participant participant : originalDraw.participants()) {
            exchangeService.confirmReceipt(participant.userId(), originalDraw.drawGeneration());
        }

        assertEquals(PendingDrawService.Status.COMPLETED, service.replaceDraw().status());

        ExchangeState replacement = exchangeService.state();
        assertEquals(originalDraw.drawGeneration() + 1, replacement.drawGeneration());
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, replacement.phase());
        assertTrue(replacement.confirmedUserIds().isEmpty());
        assertEquals(
                replacement.participants().stream()
                        .map(ExchangeState.Participant::userId)
                        .collect(java.util.stream.Collectors.toSet()),
                replacement.deliveredUserIds());
        assertTrue(replacement.assignments().stream().noneMatch(assignment ->
                (assignment.giverId() == 201L && assignment.receiverId() == 202L)
                        || (assignment.giverId() == 202L && assignment.receiverId() == 201L)));
        List<SentMessage> replacementMessages = messenger.messages().subList(4, 8);
        assertEquals(4, replacementMessages.size());
        for (ExchangeState.Assignment assignment : replacement.assignments()) {
            SentMessage message = replacementMessages.stream()
                    .filter(sent -> sent.chatId() == assignment.giverId())
                    .findFirst()
                    .orElseThrow();
            String receiverName = exchangeService.participant(assignment.receiverId())
                    .orElseThrow()
                    .name();
            assertTrue(message.text().contains(receiverName));
            replacement.participants().stream()
                    .filter(participant -> participant.userId() != assignment.receiverId())
                    .forEach(participant -> assertFalse(message.text().contains(participant.name())));
            assertTrue(message.callbackData().contains(
                    BotButtons.DRAW_RECEIVED_PREFIX + replacement.drawGeneration()));
        }
    }

    @Test
    void replacementStateIsPersistedBeforeSendingAnyReplacementAssignment() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        ExchangeStore exchangeStore = new ExchangeStore(dataDirectory, KEY);
        new PendingDrawService(
                        new PendingDrawStore(dataDirectory, KEY),
                        exchangeService,
                        organizerService,
                        new RecordingMessenger(null))
                .run();

        boolean[] firstReplacementSend = {true};
        TelegramMessenger verifyingMessenger = new TelegramMessenger() {
            @Override
            public void sendMessage(
                    long chatId, String text, InlineKeyboardMarkup keyboard)
                    throws TelegramApiException {
                try {
                    ExchangeState persisted = exchangeStore.load().orElseThrow();
                    assertEquals(ExchangeState.Phase.DELIVERING, persisted.phase());
                    assertEquals(2, persisted.drawGeneration());
                    assertEquals(4, persisted.assignments().size());
                    if (firstReplacementSend[0]) {
                        assertTrue(persisted.deliveredUserIds().isEmpty());
                        firstReplacementSend[0] = false;
                    }
                } catch (IOException exception) {
                    throw new TelegramApiException(exception);
                }
            }

            @Override
            public void answerCallback(String callbackQueryId, String text) {}
        };
        PendingDrawService replacementService = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                verifyingMessenger);

        assertEquals(
                PendingDrawService.Status.COMPLETED,
                replacementService.replaceDraw().status());
    }

    @Test
    void failedReplacementPersistenceKeepsThePreviousDrawAndSendsNothing() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService originalExchangeService =
                createExchangeWithThreeParticipants(organizerService);
        new PendingDrawService(
                        new PendingDrawStore(dataDirectory, KEY),
                        originalExchangeService,
                        organizerService,
                        new RecordingMessenger(null))
                .run();
        ExchangeState originalDraw = originalExchangeService.state();
        ExchangeService failingExchangeService = new ExchangeService(
                organizerService,
                new ExchangeStore(
                        dataDirectory,
                        KEY,
                        (source, target) -> {
                            throw new IOException("Simulated replacement state write failure.");
                        }));
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                failingExchangeService,
                organizerService,
                messenger);

        assertThrows(IOException.class, service::replaceDraw);

        assertTrue(messenger.messages().isEmpty());
        assertEquals(originalDraw, failingExchangeService.state());
        assertEquals(
                originalDraw,
                new ExchangeStore(dataDirectory, KEY).load().orElseThrow());
    }

    @Test
    void replacementDrawIsRejectedWhileDeliveryIsIncomplete() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        RecordingMessenger messenger = new RecordingMessenger(202L);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                messenger);

        assertEquals(PendingDrawService.Status.PENDING, service.run().status());
        int messageCountBeforeReplacement = messenger.messages().size();

        assertThrows(IllegalStateException.class, service::replaceDraw);

        assertEquals(messageCountBeforeReplacement, messenger.messages().size());
        assertEquals(ExchangeState.Phase.DELIVERING, exchangeService.state().phase());
    }

    @Test
    void replacementDeliveryResumesPersistedGenerationAfterRestart() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        RecordingMessenger firstMessenger = new RecordingMessenger(null);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                exchangeService,
                organizerService,
                firstMessenger);
        assertEquals(PendingDrawService.Status.COMPLETED, service.run().status());

        firstMessenger.failChatId = 202L;
        PendingDrawService.Result replacementResult = service.replaceDraw();
        ExchangeState persistedReplacement = exchangeService.state();

        assertEquals(PendingDrawService.Status.PENDING, replacementResult.status());
        assertEquals(1, replacementResult.failedDeliveries());
        assertEquals(2, persistedReplacement.drawGeneration());
        assertEquals(ExchangeState.Phase.DELIVERING, persistedReplacement.phase());
        assertTrue(persistedReplacement.confirmedUserIds().isEmpty());

        OrganizerService restartedOrganizerService = createOrganizerService();
        ExchangeService restartedExchangeService =
                createExchangeService(restartedOrganizerService);
        RecordingMessenger retryMessenger = new RecordingMessenger(null);
        PendingDrawService restartedService = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                restartedExchangeService,
                restartedOrganizerService,
                retryMessenger);

        assertEquals(
                PendingDrawService.Status.COMPLETED,
                restartedService.run().status());

        ExchangeState completedReplacement = restartedExchangeService.state();
        assertEquals(persistedReplacement.assignments(), completedReplacement.assignments());
        assertEquals(persistedReplacement.drawGeneration(), completedReplacement.drawGeneration());
        assertEquals(1, retryMessenger.messages().size());
        assertTrue(retryMessenger.messages().getFirst().text().contains("Жеребкування оновлено"));
        assertTrue(retryMessenger.messages().getFirst().callbackData().contains(
                BotButtons.DRAW_RECEIVED_PREFIX + completedReplacement.drawGeneration()));
    }

    @Test
    void migratesLegacyDrawIdempotentlyBeforeRemovingLegacyFile() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        exchangeService.closeRegistration();
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        PendingDrawStore.PendingDraw legacyDraw = legacyDraw(Set.of(100L, 201L));
        store.save(legacyDraw);

        exchangeService.migrateLegacyDraw(legacyDraw);
        ExchangeService restartedExchangeService =
                createExchangeService(createOrganizerService());
        new PendingDrawService(
                store,
                restartedExchangeService,
                createOrganizerService(),
                new RecordingMessenger(null));

        ExchangeState migratedState = store.exchangeStore().load().orElseThrow();
        assertEquals(ExchangeState.Phase.DELIVERING, migratedState.phase());
        assertEquals(legacyDraw.deliveredGiverIds(), migratedState.deliveredUserIds());
        assertEquals(
                legacyDraw.assignments().stream()
                        .map(assignment -> new ExchangeState.Assignment(
                                assignment.giverId(), assignment.receiverId()))
                        .toList(),
                migratedState.assignments());
        assertEquals(migratedState, restartedExchangeService.state());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void preservesLegacyDrawWhenWritingMigratedStateFails() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        exchangeService.closeRegistration();
        PendingDrawStore legacyStore = new PendingDrawStore(dataDirectory, KEY);
        legacyStore.save(legacyDraw(Set.of(100L)));
        ExchangeStore failingExchangeStore = new ExchangeStore(
                dataDirectory,
                KEY,
                (source, target) -> {
                    throw new IOException("Simulated exchange-state write failure.");
                });
        ExchangeService failingExchangeService =
                new ExchangeService(organizerService, failingExchangeStore);

        assertThrows(
                IllegalStateException.class,
                () -> new PendingDrawService(
                        legacyStore,
                        failingExchangeService,
                        organizerService,
                        new RecordingMessenger(null)));

        assertTrue(legacyStore.load().isPresent());
        assertEquals(ExchangeState.Phase.REGISTRATION_CLOSED, exchangeService.state().phase());
    }

    @Test
    void doesNotSendOrCloseRegistrationWhenPersistingNewDrawFails() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService originalExchangeService =
                createExchangeWithThreeParticipants(organizerService);
        ExchangeService failingExchangeService = new ExchangeService(
                organizerService,
                new ExchangeStore(
                        dataDirectory,
                        KEY,
                        (source, target) -> {
                            throw new IOException("Simulated exchange-state write failure.");
                        }));
        RecordingMessenger messenger = new RecordingMessenger(null);
        PendingDrawService service = new PendingDrawService(
                new PendingDrawStore(dataDirectory, KEY),
                failingExchangeService,
                organizerService,
                messenger);

        assertThrows(IOException.class, service::run);

        assertTrue(messenger.messages().isEmpty());
        assertEquals(
                ExchangeState.Phase.REGISTRATION_OPEN,
                originalExchangeService.state().phase());
        assertEquals(
                ExchangeState.Phase.REGISTRATION_OPEN,
                new ExchangeStore(dataDirectory, KEY).load().orElseThrow().phase());
    }

    private static PendingDrawStore.PendingDraw legacyDraw(Set<Long> deliveredGiverIds) {
        return new PendingDrawStore.PendingDraw(
                List.of(
                        new PendingDrawStore.Assignment(100L, "Organizer", 201L, "Guest 201"),
                        new PendingDrawStore.Assignment(201L, "Guest 201", 202L, "Guest 202"),
                        new PendingDrawStore.Assignment(202L, "Guest 202", 203L, "Guest 203"),
                        new PendingDrawStore.Assignment(203L, "Guest 203", 100L, "Organizer")),
                deliveredGiverIds);
    }

    private OrganizerService createOrganizerService() throws Exception {
        OrganizerService service =
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
        service.claim(100L, "setup-code");
        return service;
    }

    private ExchangeService createExchangeWithThreeParticipants(
            OrganizerService organizerService) throws Exception {
        ExchangeService exchange = createExchangeService(organizerService);
        String inviteCode = exchange.openRegistration(100L);
        for (long userId = 201L; userId <= 203L; userId++) {
            assertEquals(
                    ExchangeService.RegistrationResult.REGISTERED,
                    exchange.register(userId, inviteCode, "Guest " + userId));
        }
        return exchange;
    }

    private ExchangeService createExchangeService(OrganizerService organizerService) {
        return new ExchangeService(organizerService, new ExchangeStore(dataDirectory, KEY));
    }

    private record SentMessage(long chatId, String text, String callbackData) {}

    private static final class RecordingMessenger implements TelegramMessenger {
        private final List<SentMessage> sentMessages = new ArrayList<>();
        private Long failChatId;

        RecordingMessenger(Long failChatId) {
            this.failChatId = failChatId;
        }

        List<SentMessage> messages() {
            return List.copyOf(sentMessages);
        }

        @Override
        public void sendMessage(
                long chatId, String text, InlineKeyboardMarkup keyboard)
                throws TelegramApiException {
            sentMessages.add(new SentMessage(
                    chatId,
                    text,
                    keyboard.getKeyboard().stream()
                            .flatMap(List::stream)
                            .map(button -> button.getCallbackData())
                            .findFirst()
                            .orElse("")));
            if (failChatId != null && failChatId == chatId) {
                throw new TelegramApiException("Simulated delivery failure.");
            }
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {}
    }
}
