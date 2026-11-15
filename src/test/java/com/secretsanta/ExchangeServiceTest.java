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
    void lateRegistrationCannotOpenBeforeTheDrawIsFullyDelivered() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchangeWithParticipants(organizerService);
        assertThrows(
                IllegalStateException.class,
                () -> exchange.openLateRegistration(100L));

        exchange.beginDraw(List.of(
                new ExchangeState.Assignment(100L, 201L),
                new ExchangeState.Assignment(201L, 202L),
                new ExchangeState.Assignment(202L, 203L),
                new ExchangeState.Assignment(203L, 204L),
                new ExchangeState.Assignment(204L, 100L)));
        exchange.markAssignmentDelivered(100L);
        ExchangeState partiallyDelivered = exchange.state();

        assertThrows(
                IllegalStateException.class,
                () -> exchange.openLateRegistration(100L));
        assertEquals(partiallyDelivered, exchange.state());
    }

    @Test
    void lateRegistrationUsesFreshPrivateWindowCodeAndRetainsDrawAcrossRestart()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchangeWithParticipants(organizerService);
        String initialInviteCode = exchange.state().inviteCode();
        completeFivePersonDraw(exchange);
        exchange.confirmReceipt(100L, exchange.state().drawGeneration());
        ExchangeState previousDraw = exchange.state();

        assertThrows(
                SecurityException.class,
                () -> exchange.openLateRegistration(201L));
        assertEquals(previousDraw, exchange.state());

        String lateInviteCode = exchange.openLateRegistration(100L);
        ExchangeState lateRegistration = exchange.state();
        assertEquals(ExchangeState.Phase.LATE_REGISTRATION_OPEN, lateRegistration.phase());
        assertFalse(lateInviteCode.isBlank());
        assertFalse(lateInviteCode.equals(initialInviteCode));
        assertEquals(lateInviteCode, lateRegistration.inviteCode());
        assertEquals(previousDraw.assignments(), lateRegistration.assignments());
        assertEquals(previousDraw.deliveredUserIds(), lateRegistration.deliveredUserIds());
        assertEquals(previousDraw.confirmedUserIds(), lateRegistration.confirmedUserIds());
        assertEquals(previousDraw.drawGeneration(), lateRegistration.drawGeneration());
        assertTrue(exchange.hasActiveDraw());
        assertEquals(ExchangeService.InviteStatus.VALID, exchange.inviteStatus(lateInviteCode));
        assertEquals(ExchangeService.InviteStatus.INVALID, exchange.inviteStatus(initialInviteCode));
        assertEquals(lateInviteCode, exchange.openLateRegistration(100L));

        OrganizerService restartedOrganizerService =
                new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
        ExchangeService restartedExchange = new ExchangeService(
                restartedOrganizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));
        assertEquals(ExchangeService.InviteStatus.VALID, restartedExchange.inviteStatus(lateInviteCode));
        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                restartedExchange.register(301L, lateInviteCode, "Late friend"));
        assertEquals(
                ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                restartedExchange.state().phase());
        assertEquals(6, restartedExchange.state().participants().size());
    }

    @Test
    void lateInviteJoinPreservesOldDrawAndCannotConfirmAnOldAssignment() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createCompletedDraw(organizerService);
        ExchangeState previousDraw = exchange.state();
        String lateInviteCode = exchange.openLateRegistration(100L);

        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(301L, lateInviteCode, "Late friend"));
        ExchangeState updatedRoster = exchange.state();
        assertEquals(6, updatedRoster.participants().size());
        assertEquals(
                ExchangeService.RegistrationResult.INVALID_INVITE,
                exchange.register(302L, "invalid-code", "Another friend"));
        assertEquals(
                ExchangeService.RegistrationResult.ALREADY_REGISTERED,
                exchange.register(301L, lateInviteCode, "Late friend"));
        assertEquals(previousDraw.assignments(), updatedRoster.assignments());
        assertEquals(previousDraw.deliveredUserIds(), updatedRoster.deliveredUserIds());
        assertEquals(previousDraw.confirmedUserIds(), updatedRoster.confirmedUserIds());
        assertEquals(previousDraw.drawGeneration(), updatedRoster.drawGeneration());
        assertTrue(updatedRoster.participants().stream()
                .filter(participant -> participant.userId() == 301L)
                .allMatch(participant -> participant.partnerId() == null));

        assertThrows(
                IllegalStateException.class,
                () -> exchange.confirmReceipt(301L, previousDraw.drawGeneration()));
    }

    @Test
    void replacementDrawClosesTheLateRegistrationInvite() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createCompletedDraw(organizerService);
        String lateInviteCode = exchange.openLateRegistration(100L);

        exchange.replaceDraw(List.of(
                new ExchangeState.Assignment(100L, 202L),
                new ExchangeState.Assignment(201L, 203L),
                new ExchangeState.Assignment(202L, 204L),
                new ExchangeState.Assignment(203L, 100L),
                new ExchangeState.Assignment(204L, 201L)));

        assertEquals(ExchangeState.Phase.DELIVERING, exchange.state().phase());
        assertEquals(ExchangeService.InviteStatus.CLOSED, exchange.inviteStatus(lateInviteCode));
        assertEquals(
                ExchangeService.RegistrationResult.REGISTRATION_CLOSED,
                exchange.register(301L, lateInviteCode, "Late friend"));
        assertFalse(organizerService.isRegistrationOpen());
    }

    @Test
    void failedLateRegistrationWriteLeavesCompletedDrawAndInviteClosed() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService workingExchange = createCompletedDraw(organizerService);
        ExchangeState completedDraw = workingExchange.state();
        ExchangeService failingExchange = new ExchangeService(
                organizerService,
                new ExchangeStore(
                        dataDirectory,
                        TEST_DATA_KEY,
                        (source, target) -> {
                            throw new IOException("Simulated late-registration write failure.");
                        }));

        assertThrows(
                IOException.class,
                () -> failingExchange.openLateRegistration(100L));

        assertEquals(completedDraw, failingExchange.state());
        assertFalse(organizerService.isRegistrationOpen());
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
    void participantAndOrganizerCanUpdateNamesWithoutChangingPairings() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = createExchangeWithParticipants(organizerService);
        exchange.pair(201L, 202L);

        assertEquals(
                ExchangeService.NameUpdateResult.UPDATED,
                exchange.updateParticipantName(201L, 201L, "  Alex Updated  "));
        assertEquals(
                ExchangeService.NameUpdateResult.UPDATED,
                exchange.updateParticipantName(100L, 202L, "Sam Updated"));

        assertEquals("Alex Updated", exchange.participant(201L).orElseThrow().name());
        assertEquals("Sam Updated", exchange.participant(202L).orElseThrow().name());
        assertEquals(202L, exchange.participant(201L).orElseThrow().partnerId());
        assertEquals(201L, exchange.participant(202L).orElseThrow().partnerId());

        ExchangeService restartedExchange = new ExchangeService(
                organizerService, new ExchangeStore(dataDirectory, TEST_DATA_KEY));

        assertEquals("Alex Updated", restartedExchange.participant(201L).orElseThrow().name());
        assertEquals("Sam Updated", restartedExchange.participant(202L).orElseThrow().name());
        assertEquals(202L, restartedExchange.participant(201L).orElseThrow().partnerId());
    }

    @Test
    void participantCannotUpdateAnotherParticipantsName() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());

        assertThrows(
                SecurityException.class,
                () -> exchange.updateParticipantName(203L, 201L, "Changed"));
        assertEquals("Guest 201", exchange.participant(201L).orElseThrow().name());
    }

    @Test
    void nameUpdatesRejectInvalidNamesAndClosedRegistration() throws Exception {
        ExchangeService exchange = createExchangeWithParticipants(createOrganizerService());

        assertEquals(
                ExchangeService.NameUpdateResult.INVALID_NAME,
                exchange.updateParticipantName(201L, 201L, "  "));
        assertEquals("Guest 201", exchange.participant(201L).orElseThrow().name());

        exchange.closeRegistration();
        assertThrows(
                IllegalStateException.class,
                () -> exchange.updateParticipantName(201L, 201L, "Changed"));
        assertEquals("Guest 201", exchange.participant(201L).orElseThrow().name());
    }

    @Test
    void failedNameUpdateWriteDoesNotChangeTheRoster() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeStore workingStore = new ExchangeStore(dataDirectory, TEST_DATA_KEY);
        ExchangeService exchange = new ExchangeService(organizerService, workingStore);
        String inviteCode = exchange.openRegistration(100L);
        exchange.register(201L, inviteCode, "Alex");
        ExchangeStore failingStore = new ExchangeStore(
                dataDirectory,
                TEST_DATA_KEY,
                (source, target) -> {
                    throw new IOException("Simulated atomic write failure.");
                });
        ExchangeService failingExchange = new ExchangeService(organizerService, failingStore);

        assertThrows(
                IOException.class,
                () -> failingExchange.updateParticipantName(201L, 201L, "Alex Updated"));

        assertEquals("Alex", failingExchange.participant(201L).orElseThrow().name());
        assertEquals("Alex", workingStore.load().orElseThrow().participants().get(1).name());
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
        completeFivePersonDraw(exchange);
        return exchange;
    }

    private static void completeFivePersonDraw(ExchangeService exchange) throws Exception {
        exchange.beginDraw(List.of(
                new ExchangeState.Assignment(100L, 201L),
                new ExchangeState.Assignment(201L, 202L),
                new ExchangeState.Assignment(202L, 203L),
                new ExchangeState.Assignment(203L, 204L),
                new ExchangeState.Assignment(204L, 100L)));
        for (long userId : List.of(100L, 201L, 202L, 203L, 204L)) {
            exchange.markAssignmentDelivered(userId);
        }
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
