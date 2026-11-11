package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OrganizerServiceTest {
    @TempDir
    Path dataDirectory;

    @Test
    void onlyTheFirstValidClaimCanSetTheOrganizer() throws Exception {
        OrganizerService service = createService(dataDirectory);

        assertEquals(
                OrganizerService.ClaimResult.INVALID_CODE,
                service.claim(100L, "wrong-code"));
        assertEquals(
                OrganizerService.ClaimResult.CLAIMED,
                service.claim(101L, "setup-code"));
        assertEquals(
                OrganizerService.ClaimResult.ALREADY_CLAIMED,
                service.claim(102L, "setup-code"));
        assertTrue(service.isOrganizer(101L));
        assertFalse(service.isOrganizer(102L));
        assertEquals(
                "101",
                Files.readString(dataDirectory.resolve("organizer-id")));
    }

    @Test
    void organizerIdentityPersistsButRegistrationDoesNotSurviveRestart() throws Exception {
        OrganizerService service = createService(dataDirectory);
        assertEquals(
                OrganizerService.ClaimResult.CLAIMED,
                service.claim(101L, "setup-code"));
        service.openRegistration(101L);

        OrganizerService restarted = createService(dataDirectory);

        assertTrue(restarted.isOrganizer(101L));
        assertFalse(restarted.isRegistrationOpen());
    }

    @Test
    void onlyTheOrganizerCanOpenRegistration() throws Exception {
        OrganizerService service = createService(dataDirectory);
        service.claim(101L, "setup-code");

        assertThrows(SecurityException.class, () -> service.openRegistration(102L));
        assertFalse(service.isRegistrationOpen());
    }

    @Test
    void openingRegistrationCreatesANewInviteCode() throws Exception {
        OrganizerService service = createService(dataDirectory);
        service.claim(101L, "setup-code");

        String firstCode = service.openRegistration(101L);
        String secondCode = service.openRegistration(101L);

        assertNotEquals(firstCode, secondCode);
        assertTrue(firstCode.matches("[A-Za-z0-9_-]{32}"));
        assertTrue(secondCode.matches("[A-Za-z0-9_-]{32}"));
        assertTrue(service.isRegistrationOpen());
    }

    private static OrganizerService createService(Path dataDirectory) {
        return new OrganizerService(new OrganizerStore(dataDirectory), "setup-code");
    }
}
