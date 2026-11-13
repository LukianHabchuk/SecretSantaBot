package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExchangeServiceTest {
    private static final byte[] TEST_DATA_KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void persistsOrganizerAndParticipantEnrollmentAcrossRestart() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        String inviteCode = exchange.openRegistration(100L, "Organizer");
        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(201L, inviteCode, "Alex"));

        OrganizerService restartedOrganizerService =
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
        ExchangeService restartedExchange = new ExchangeService(
                restartedOrganizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        assertEquals("Organizer", restartedExchange.participant(100L).orElseThrow().name());
        assertEquals("Alex", restartedExchange.participant(201L).orElseThrow().name());
        assertEquals(inviteCode, restartedExchange.openRegistration(100L, "New name"));
        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                restartedExchange.register(202L, inviteCode, "Sam"));
    }

    @Test
    void preDrawResetRequiresConfirmationAndClearsOnlyTheExchange() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        String inviteCode = exchange.openRegistration(100L, "Organizer");
        exchange.register(201L, inviteCode, "Alex");

        assertFalse(exchange.resetRegistration(100L, false));
        assertEquals(2, exchange.participants().size());
        assertTrue(exchange.resetRegistration(100L, true));

        assertTrue(exchange.participants().isEmpty());
        assertEquals(
                ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                exchange.register(202L, inviteCode, "Sam"));
        assertTrue(organizerService.isOrganizer(100L));
    }

    @Test
    void closedRegistrationSurvivesRestart() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeStore store = new ExchangeStore(dataDirectory, TEST_DATA_KEY);
        ExchangeService exchange = new ExchangeService(organizerService, store);
        String inviteCode = exchange.openRegistration(100L, "Organizer");
        exchange.closeRegistration();

        ExchangeService restartedExchange = new ExchangeService(
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code"),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        assertEquals(
                ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                restartedExchange.register(201L, inviteCode, "Alex"));
    }

    @Test
    void resetIsRejectedAfterRegistrationCloses() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchange(organizerService);
        String inviteCode = exchange.openRegistration(100L);
        exchange.register(201L, inviteCode, "Alex");
        exchange.closeRegistration();

        assertThrows(
                IllegalStateException.class,
                () -> exchange.resetRegistration(100L, true));
        assertEquals(2, exchange.participants().size());
    }

    @Test
    void failedRegistrationWriteDoesNotChangeTheInMemoryOrPersistedRoster()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeStore workingStore = new ExchangeStore(dataDirectory, TEST_DATA_KEY);
        ExchangeService exchange = new ExchangeService(organizerService, workingStore);
        String inviteCode = exchange.openRegistration(100L, "Organizer");
        ExchangeStore failingStore = new ExchangeStore(
                dataDirectory,
                TEST_DATA_KEY,
                (source, target) -> {
                    throw new IOException("Simulated atomic write failure.");
                });
        ExchangeService failingExchange = new ExchangeService(organizerService, failingStore);

        assertThrows(
                IOException.class,
                () -> failingExchange.register(201L, inviteCode, "Alex"));

        assertEquals(1, failingExchange.participants().size());
        assertEquals(1, workingStore.load().orElseThrow().participants().size());
    }

    @Test
    void onlyTheCurrentInviteCodeCanRegisterParticipants() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchange(organizerService);

        assertEquals(
                ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                exchange.register(201L, "unknown", "Alex"));
        String inviteCode = exchange.openRegistration(100L);

        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(201L, inviteCode, "Alex"));
        assertEquals(
                ExchangeService.RegistrationResult.INVALID_INVITE,
                exchange.register(202L, "wrong-code", "Sam"));
        assertEquals(2, exchange.participants().size());
    }

    @Test
    void repeatedRegistrationDoesNotCreateAnotherParticipantOrReplaceTheirName()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchange(organizerService);
        String inviteCode = exchange.openRegistration(100L);

        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(201L, inviteCode, "Alex"));
        assertEquals(
                ExchangeService.RegistrationResult.ALREADY_REGISTERED,
                exchange.register(201L, inviteCode, "Different name"));

        assertEquals(2, exchange.participants().size());
        assertEquals("Alex", exchange.participant(201L).orElseThrow().name());
    }

    @Test
    void reopeningRegistrationResumesTheExistingCodeAndRoster()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchange(organizerService);
        String oldInviteCode = exchange.openRegistration(100L);
        exchange.register(201L, oldInviteCode, "Alex");

        String newInviteCode = exchange.openRegistration(100L);

        assertEquals(oldInviteCode, newInviteCode);
        assertEquals("Alex", exchange.participant(201L).orElseThrow().name());
        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(202L, oldInviteCode, "Sam"));
        assertEquals(3, exchange.participants().size());
    }

    @Test
    void rejectsBlankOverlongAndControlCharacterNames() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchange(organizerService);
        String inviteCode = exchange.openRegistration(100L);

        assertEquals(
                ExchangeService.RegistrationResult.INVALID_NAME,
                exchange.register(201L, inviteCode, "  "));
        assertEquals(
                ExchangeService.RegistrationResult.INVALID_NAME,
                exchange.register(202L, inviteCode, "A".repeat(65)));
        assertEquals(
                ExchangeService.RegistrationResult.INVALID_NAME,
                exchange.register(203L, inviteCode, "Alex\nSam"));
        assertEquals(1, exchange.participants().size());
    }

    @Test
    void pairingIsMutualAndReplacesBothPreviousPairs() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchangeWithParticipants(organizerService);

        exchange.pair(201L, 202L);
        exchange.pair(203L, 204L);
        ExchangeService.PairingChange change = exchange.pair(201L, 203L);

        assertEquals(203L, exchange.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, exchange.participant(203L).orElseThrow().partnerId());
        assertNull(exchange.participant(202L).orElseThrow().partnerId());
        assertNull(exchange.participant(204L).orElseThrow().partnerId());
        assertEquals(202L, change.formerPartnerOfFirst().chatId());
        assertEquals(204L, change.formerPartnerOfSecond().chatId());
    }

    @Test
    void rejectsSelfPairingAndParticipantsOutsideTheRoster() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());

        assertThrows(IllegalArgumentException.class, () -> exchange.pair(201L, 201L));
        assertThrows(IllegalArgumentException.class, () -> exchange.pair(201L, 999L));
    }

    @Test
    void participantPairRequestMustBeAcceptedAndSurvivesRestart() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchangeWithParticipants(organizerService);

        exchange.requestPair(201L, 202L);
        ExchangeService restartedExchange = new ExchangeService(
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code"),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        assertEquals(
                List.of(new ExchangeState.PairRequest(201L, 202L)),
                restartedExchange.state().pairRequests());
        restartedExchange.acceptPairRequest(202L, 201L);

        assertEquals(202L, restartedExchange.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, restartedExchange.participant(202L).orElseThrow().partnerId());
        assertTrue(restartedExchange.state().pairRequests().isEmpty());

        ExchangeService acceptedPairAfterRestart = new ExchangeService(
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code"),
                new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(202L, acceptedPairAfterRestart.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, acceptedPairAfterRestart.participant(202L).orElseThrow().partnerId());
        assertTrue(acceptedPairAfterRestart.state().pairRequests().isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> acceptedPairAfterRestart.acceptPairRequest(202L, 201L));
    }

    @Test
    void pairRequestsRejectInvalidOrAlreadyBusyParticipants() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());
        exchange.pair(201L, 202L);
        exchange.requestPair(203L, 204L);

        assertThrows(IllegalArgumentException.class, () -> exchange.requestPair(203L, 203L));
        assertThrows(IllegalArgumentException.class, () -> exchange.requestPair(203L, 999L));
        assertThrows(IllegalArgumentException.class, () -> exchange.requestPair(201L, 204L));
        assertThrows(IllegalArgumentException.class, () -> exchange.requestPair(204L, 203L));
        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.acceptPairRequest(203L, 204L));
        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.declinePairRequest(999L, 203L));
        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.cancelPairRequest(203L, 999L));
        assertEquals(
                List.of(new ExchangeState.PairRequest(203L, 204L)),
                exchange.state().pairRequests());
    }

    @Test
    void decliningAndCancelingRequestsOnlyRemoveTheAuthorizedRequest() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());
        exchange.requestPair(201L, 202L);
        exchange.requestPair(203L, 204L);

        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.declinePairRequest(201L, 202L));
        exchange.declinePairRequest(202L, 201L);
        assertEquals(
                List.of(new ExchangeState.PairRequest(203L, 204L)),
                exchange.state().pairRequests());

        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.cancelPairRequest(204L, 203L));
        exchange.cancelPairRequest(203L, 204L);
        assertTrue(exchange.state().pairRequests().isEmpty());
    }

    @Test
    void organizerPairingClearsRequestsForAffectedParticipants() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());
        exchange.requestPair(201L, 202L);

        exchange.pair(201L, 203L);

        assertTrue(exchange.state().pairRequests().isEmpty());
        assertEquals(203L, exchange.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, exchange.participant(203L).orElseThrow().partnerId());
        assertNull(exchange.participant(202L).orElseThrow().partnerId());
    }

    @Test
    void registrationCannotCloseWhilePairRequestsAreUnresolved() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());
        exchange.requestPair(201L, 202L);

        assertThrows(IllegalStateException.class, exchange::closeRegistration);

        assertEquals(ExchangeState.Phase.REGISTRATION_OPEN, exchange.state().phase());
        assertEquals(1, exchange.state().pairRequests().size());
    }

    @Test
    void participantReceiptIsBoundToCurrentDrawAndIdempotent() throws Exception {
        ExchangeService exchange = createCompletedDraw(createOrganizerService());
        long generation = exchange.state().drawGeneration();

        assertTrue(exchange.confirmReceipt(201L, generation));
        assertFalse(exchange.confirmReceipt(201L, generation));
        assertEquals(java.util.Set.of(201L), exchange.state().confirmedUserIds());
        assertThrows(
                IllegalArgumentException.class,
                () -> exchange.confirmReceipt(999L, generation));
        assertThrows(
                IllegalStateException.class,
                () -> exchange.confirmReceipt(202L, generation + 1));
    }

    @Test
    void cleanupRequiresOrganizerAndEveryParticipantConfirmation() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createCompletedDraw(organizerService);
        long generation = exchange.state().drawGeneration();

        assertThrows(
                IllegalStateException.class,
                () -> exchange.cleanupExchange(100L, true));
        assertThrows(
                SecurityException.class,
                () -> exchange.cleanupExchange(201L, true));
        assertFalse(exchange.cleanupExchange(100L, false));
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, exchange.state().phase());

        exchange.state().participants().forEach(participant -> {
            try {
                exchange.confirmReceipt(participant.userId(), generation);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
        assertTrue(exchange.cleanupExchange(100L, true));

        assertEquals(ExchangeState.Phase.EMPTY, exchange.state().phase());
        assertTrue(exchange.participants().isEmpty());
        assertTrue(organizerService.isOrganizer(100L));
    }

    @Test
    void receiptConfirmationsSurviveRestartAndCleanupRequiresAllParticipants() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createCompletedDraw(organizerService);
        long generation = exchange.state().drawGeneration();
        exchange.confirmReceipt(100L, generation);
        exchange.confirmReceipt(201L, generation);

        ExchangeService restartedExchange = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        assertEquals(
                java.util.Set.of(100L, 201L),
                restartedExchange.state().confirmedUserIds());
        assertThrows(
                IllegalStateException.class,
                () -> restartedExchange.cleanupExchange(100L, true));
    }

    private ExchangeService createCompletedDraw(OrganizerService organizerService)
            throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(organizerService);
        exchange.beginDraw(List.of(
                new ExchangeState.Assignment(100L, 201L),
                new ExchangeState.Assignment(201L, 202L),
                new ExchangeState.Assignment(202L, 203L),
                new ExchangeState.Assignment(203L, 204L),
                new ExchangeState.Assignment(204L, 100L)));
        for (long userId : List.of(100L, 201L, 202L, 203L, 204L)) {
            exchange.markAssignmentDelivered(userId);
        }
        return exchange;
    }

    private OrganizerService createOrganizerService() throws Exception {
        OrganizerService service =
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
        assertEquals(
                OrganizerService.ClaimResult.CLAIMED,
                service.claim(100L, "setup-code"));
        return service;
    }

    private ExchangeService createExchangeWithParticipants(OrganizerService organizerService)
            throws Exception {
        ExchangeService exchange = createExchange(organizerService);
        String inviteCode = exchange.openRegistration(100L);
        for (long userId = 201L; userId <= 204L; userId++) {
            assertEquals(
                    ExchangeService.RegistrationResult.REGISTERED,
                    exchange.register(userId, inviteCode, "Guest " + userId));
        }
        return exchange;
    }

    private ExchangeService createExchange(OrganizerService organizerService) {
        return new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
    }
}
