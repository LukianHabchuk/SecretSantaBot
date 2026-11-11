package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExchangeServiceTest {
    @TempDir
    Path dataDirectory;

    @Test
    void onlyTheCurrentInviteCodeCanRegisterParticipants() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(organizerService);

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
        assertEquals(1, exchange.participants().size());
    }

    @Test
    void repeatedRegistrationDoesNotCreateAnotherParticipantOrReplaceTheirName()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(organizerService);
        String inviteCode = exchange.openRegistration(100L);

        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(201L, inviteCode, "Alex"));
        assertEquals(
                ExchangeService.RegistrationResult.ALREADY_REGISTERED,
                exchange.register(201L, inviteCode, "Different name"));

        assertEquals(1, exchange.participants().size());
        assertEquals("Alex", exchange.participant(201L).orElseThrow().name());
    }

    @Test
    void reopeningRegistrationInvalidatesThePreviousCodeAndClearsTheRoster()
            throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(organizerService);
        String oldInviteCode = exchange.openRegistration(100L);
        exchange.register(201L, oldInviteCode, "Alex");

        String newInviteCode = exchange.openRegistration(100L);

        assertTrue(exchange.participants().isEmpty());
        assertEquals(
                ExchangeService.RegistrationResult.INVALID_INVITE,
                exchange.register(202L, oldInviteCode, "Sam"));
        assertEquals(
                ExchangeService.RegistrationResult.REGISTERED,
                exchange.register(202L, newInviteCode, "Sam"));
    }

    @Test
    void rejectsBlankOverlongAndControlCharacterNames() throws Exception {
        OrganizerService organizerService = createOrganizerService();
        ExchangeService exchange = new ExchangeService(organizerService);
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
        assertTrue(exchange.participants().isEmpty());
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
        ExchangeService exchange = new ExchangeService(organizerService);
        String inviteCode = exchange.openRegistration(100L);
        for (long userId = 201L; userId <= 204L; userId++) {
            assertEquals(
                    ExchangeService.RegistrationResult.REGISTERED,
                    exchange.register(userId, inviteCode, "Guest " + userId));
        }
        return exchange;
    }
}
