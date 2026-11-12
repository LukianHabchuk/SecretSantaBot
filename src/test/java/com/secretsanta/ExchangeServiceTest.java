package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
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
