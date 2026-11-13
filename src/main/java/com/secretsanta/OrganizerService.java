package com.secretsanta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.OptionalLong;

final class OrganizerService {
    enum ClaimResult {
        CLAIMED,
        INVALID_CODE,
        ALREADY_CLAIMED
    }

    enum InviteCodeStatus {
        VALID,
        INVALID,
        CLOSED
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int INVITE_CODE_BYTES = 24;

    private final OrganizerStore store;
    private final String setupCode;
    private String activeInviteCode;

    OrganizerService(OrganizerStore store, String setupCode) {
        if (store == null || setupCode == null || setupCode.isBlank()) {
            throw new IllegalArgumentException("Organizer store and setup code are required.");
        }
        this.store = store;
        this.setupCode = setupCode;
    }

    ClaimResult claim(long telegramUserId, String providedCode) throws IOException {
        if (telegramUserId <= 0) {
            throw new IllegalArgumentException("Telegram user ID must be positive.");
        }
        byte[] expected = setupCode.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedCode == null
                ? new byte[0]
                : providedCode.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided)) {
            return ClaimResult.INVALID_CODE;
        }
        return store.claim(telegramUserId) ? ClaimResult.CLAIMED : ClaimResult.ALREADY_CLAIMED;
    }

    boolean isOrganizer(long telegramUserId) throws IOException {
        return store.organizerId().stream().anyMatch(id -> id == telegramUserId);
    }

    OptionalLong organizerId() throws IOException {
        return store.organizerId();
    }

    synchronized String openRegistration(long telegramUserId) throws IOException {
        if (!isOrganizer(telegramUserId)) {
            throw new SecurityException("Only the organizer can open registration.");
        }

        byte[] randomBytes = new byte[INVITE_CODE_BYTES];
        RANDOM.nextBytes(randomBytes);
        activeInviteCode = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return activeInviteCode;
    }

    synchronized boolean isRegistrationOpen() {
        return activeInviteCode != null;
    }

    synchronized void closeRegistration() {
        activeInviteCode = null;
    }

    synchronized InviteCodeStatus validateInviteCode(String providedCode) {
        if (activeInviteCode == null) {
            return InviteCodeStatus.CLOSED;
        }
        byte[] expected = activeInviteCode.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedCode == null
                ? new byte[0]
                : providedCode.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided)
                ? InviteCodeStatus.VALID
                : InviteCodeStatus.INVALID;
    }
}
