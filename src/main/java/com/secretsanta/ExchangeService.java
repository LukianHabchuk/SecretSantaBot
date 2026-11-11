package com.secretsanta;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class ExchangeService {
    enum RegistrationResult {
        REGISTERED,
        INVALID_INVITE,
        REGISTRATION_CLOSED,
        ALREADY_REGISTERED,
        INVALID_NAME
    }

    record PairingChange(
            SecretSantaBot.Participant first,
            SecretSantaBot.Participant second,
            SecretSantaBot.Participant formerPartnerOfFirst,
            SecretSantaBot.Participant formerPartnerOfSecond,
            boolean unchanged) {}

    private static final int MAX_NAME_CODE_POINTS = 64;

    private final OrganizerService organizerService;
    private final Map<Long, SecretSantaBot.Participant> participants = new LinkedHashMap<>();

    ExchangeService(OrganizerService organizerService) {
        this.organizerService = Objects.requireNonNull(organizerService);
    }

    synchronized String openRegistration(long organizerId) throws IOException {
        String inviteCode = organizerService.openRegistration(organizerId);
        participants.clear();
        return inviteCode;
    }

    synchronized RegistrationResult register(long userId, String inviteCode, String name) {
        if (userId <= 0) {
            throw new IllegalArgumentException("Telegram user ID must be positive.");
        }

        OrganizerService.InviteCodeStatus inviteStatus =
                organizerService.validateInviteCode(inviteCode);
        if (inviteStatus == OrganizerService.InviteCodeStatus.CLOSED) {
            return RegistrationResult.REGISTRATION_CLOSED;
        }
        if (inviteStatus == OrganizerService.InviteCodeStatus.INVALID) {
            return RegistrationResult.INVALID_INVITE;
        }
        if (participants.containsKey(userId)) {
            return RegistrationResult.ALREADY_REGISTERED;
        }

        String displayName = validDisplayName(name);
        if (displayName == null) {
            return RegistrationResult.INVALID_NAME;
        }
        participants.put(userId, new SecretSantaBot.Participant(userId, displayName, null));
        return RegistrationResult.REGISTERED;
    }

    synchronized List<SecretSantaBot.Participant> participants() {
        return List.copyOf(participants.values());
    }

    synchronized Optional<SecretSantaBot.Participant> participant(long userId) {
        return Optional.ofNullable(participants.get(userId));
    }

    synchronized PairingChange pair(long firstId, long secondId) {
        if (firstId == secondId) {
            throw new IllegalArgumentException("A participant cannot be paired with themselves.");
        }
        SecretSantaBot.Participant first = participants.get(firstId);
        SecretSantaBot.Participant second = participants.get(secondId);
        if (first == null || second == null) {
            throw new IllegalArgumentException("Both participants must be registered.");
        }
        if (Objects.equals(first.partnerId(), secondId)
                && Objects.equals(second.partnerId(), firstId)) {
            return new PairingChange(first, second, null, null, true);
        }

        SecretSantaBot.Participant formerPartnerOfFirst =
                first.partnerId() == null ? null : participants.get(first.partnerId());
        SecretSantaBot.Participant formerPartnerOfSecond =
                second.partnerId() == null ? null : participants.get(second.partnerId());
        clearPartner(first.partnerId(), firstId);
        clearPartner(second.partnerId(), secondId);

        SecretSantaBot.Participant pairedFirst =
                new SecretSantaBot.Participant(first.chatId(), first.name(), secondId);
        SecretSantaBot.Participant pairedSecond =
                new SecretSantaBot.Participant(second.chatId(), second.name(), firstId);
        participants.put(firstId, pairedFirst);
        participants.put(secondId, pairedSecond);
        return new PairingChange(
                pairedFirst, pairedSecond, formerPartnerOfFirst, formerPartnerOfSecond, false);
    }

    synchronized void clearParticipants() {
        participants.clear();
    }

    private void clearPartner(Long formerPartnerId, long participantId) {
        if (formerPartnerId == null) {
            return;
        }
        SecretSantaBot.Participant formerPartner = participants.get(formerPartnerId);
        if (formerPartner != null && Objects.equals(formerPartner.partnerId(), participantId)) {
            participants.put(
                    formerPartnerId,
                    new SecretSantaBot.Participant(
                            formerPartner.chatId(), formerPartner.name(), null));
        }
    }

    private static String validDisplayName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.strip();
        if (normalized.isBlank()
                || normalized.codePointCount(0, normalized.length()) > MAX_NAME_CODE_POINTS
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return normalized;
    }
}
