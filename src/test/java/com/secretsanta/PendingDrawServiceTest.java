package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

class PendingDrawServiceTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void retriesThePersistedDrawAfterRestartWithoutRedrawing() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = createExchangeWithThreeParticipants(organizerService);
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        RecordingMessenger firstMessenger = new RecordingMessenger(202L);
        PendingDrawService firstService =
                new PendingDrawService(store, exchangeService, organizerService, firstMessenger);

        PendingDrawService.Result firstResult = firstService.run();
        PendingDrawStore.PendingDraw persistedDraw = store.load().orElseThrow();

        assertEquals(PendingDrawService.Status.PENDING, firstResult.status());
        assertEquals(1, firstResult.failedDeliveries());
        assertEquals(2, persistedDraw.deliveredGiverIds().size());
        assertFalse(organizerService.isRegistrationOpen());

        OrganizerService restartedOrganizerService = createOrganizerService();
        ExchangeService restartedExchangeService = new ExchangeService(restartedOrganizerService);
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
        PendingDrawStore.Assignment failedAssignment = persistedDraw.assignments().stream()
                .filter(assignment -> assignment.giverId() == 202L)
                .findFirst()
                .orElseThrow();
        assertTrue(retryMessenger.messages().getFirst().text().contains(failedAssignment.receiverName()));
        assertEquals(
                firstMessenger.messages().stream()
                        .filter(message -> message.chatId() == 202L)
                        .findFirst()
                        .orElseThrow()
                        .text(),
                retryMessenger.messages().getFirst().text());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void retryInTheSameProcessClearsRosterOnlyAfterAllDeliveriesSucceed() throws Exception {
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
        assertTrue(exchangeService.participants().isEmpty());
        assertTrue(store.load().isEmpty());
        assertEquals(
                2,
                messenger.messages().stream()
                        .filter(message -> message.chatId() == 202L)
                        .count());
    }

    @Test
    void wrongKeyPreventsDeliveryAndPreservesThePendingDraw() throws Exception {
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
        PendingDrawService restartedService = new PendingDrawService(
                new PendingDrawStore(dataDirectory, wrongKey),
                new ExchangeService(createOrganizerService()),
                createOrganizerService(),
                retryMessenger);

        assertThrows(IOException.class, restartedService::run);
        assertTrue(retryMessenger.messages().isEmpty());
        assertTrue(originalStore.load().isPresent());
    }

    @Test
    void impossibleDrawSendsNothingAndLeavesRosterAvailable() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchangeService = new ExchangeService(organizerService);
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
        assertEquals(2, exchangeService.participants().size());
        assertTrue(store.load().isEmpty());
    }

    private OrganizerService createOrganizerService() throws Exception {
        OrganizerService service =
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
        service.claim(100L, "setup-code");
        return service;
    }

    private ExchangeService createExchangeWithThreeParticipants(
            OrganizerService organizerService) throws Exception {
        ExchangeService exchange = new ExchangeService(organizerService);
        String inviteCode = exchange.openRegistration(100L);
        for (long userId = 201L; userId <= 203L; userId++) {
            assertEquals(
                    ExchangeService.RegistrationResult.REGISTERED,
                    exchange.register(userId, inviteCode, "Guest " + userId));
        }
        return exchange;
    }

    private record SentMessage(long chatId, String text) {}

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
            sentMessages.add(new SentMessage(chatId, text));
            if (failChatId != null && failChatId == chatId) {
                throw new TelegramApiException("Simulated delivery failure.");
            }
        }

        @Override
        public void answerCallback(String callbackQueryId, String text) {}
    }
}
