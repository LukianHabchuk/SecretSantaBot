package com.secretsanta;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class ExchangeService {
    enum RegistrationResult {
        REGISTERED,
        INVALID_INVITE,
        REGISTRATION_CLOSED,
        ALREADY_REGISTERED,
        INVALID_NAME
    }

    enum InviteStatus {
        VALID,
        INVALID,
        CLOSED
    }

    record PairingChange(
            SecretSantaBot.Participant first,
            SecretSantaBot.Participant second,
            SecretSantaBot.Participant formerPartnerOfFirst,
            SecretSantaBot.Participant formerPartnerOfSecond,
            boolean unchanged) {}

    private static final int MAX_NAME_CODE_POINTS = 64;

    private final OrganizerService organizerService;
    private final ExchangeStore store;
    private ExchangeState state;

    ExchangeService(OrganizerService organizerService, ExchangeStore store) {
        this.organizerService = Objects.requireNonNull(organizerService);
        this.store = Objects.requireNonNull(store);
        try {
            this.state = store.load().orElseGet(ExchangeState::empty);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load persisted exchange state.", exception);
        }
    }

    synchronized String openRegistration(long organizerId) throws IOException {
        return openRegistration(organizerId, "Organizer");
    }

    synchronized String openRegistration(long organizerId, String organizerName)
            throws IOException {
        if (!organizerService.isOrganizer(organizerId)) {
            throw new SecurityException("Only the organizer can open registration.");
        }
        if (state.phase() == ExchangeState.Phase.REGISTRATION_OPEN) {
            return state.inviteCode();
        }
        if (state.phase() != ExchangeState.Phase.EMPTY) {
            throw new ExchangeInProgressException();
        }

        String displayName = validDisplayName(organizerName);
        if (displayName == null) {
            throw new IllegalArgumentException("Organizer display name is invalid.");
        }
        String inviteCode = organizerService.openRegistration(organizerId);
        ExchangeState nextState = new ExchangeState(
                ExchangeState.Phase.REGISTRATION_OPEN,
                inviteCode,
                List.of(new ExchangeState.Participant(organizerId, displayName, null)),
                List.of(),
                0,
                List.of(),
                Set.of(),
                Set.of());
        try {
            persist(nextState);
        } catch (IOException exception) {
            organizerService.closeRegistration();
            throw exception;
        }
        return inviteCode;
    }

    synchronized boolean resetRegistration(long organizerId, boolean confirmed)
            throws IOException {
        if (!organizerService.isOrganizer(organizerId)) {
            throw new SecurityException("Only the organizer can reset registration.");
        }
        if (!confirmed) {
            return false;
        }
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            throw new IllegalStateException("Only an open pre-draw exchange can be reset.");
        }
        persist(ExchangeState.empty());
        organizerService.closeRegistration();
        return true;
    }

    synchronized RegistrationResult register(long userId, String inviteCode, String name)
            throws IOException {
        if (userId <= 0) {
            throw new IllegalArgumentException("Telegram user ID must be positive.");
        }
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            return RegistrationResult.REGISTRATION_CLOSED;
        }
        if (!matchesInviteCode(state.inviteCode(), inviteCode)) {
            return RegistrationResult.INVALID_INVITE;
        }
        if (state.participants().stream().anyMatch(participant -> participant.userId() == userId)) {
            return RegistrationResult.ALREADY_REGISTERED;
        }

        String displayName = validDisplayName(name);
        if (displayName == null) {
            return RegistrationResult.INVALID_NAME;
        }
        List<ExchangeState.Participant> participants = new ArrayList<>(state.participants());
        participants.add(new ExchangeState.Participant(userId, displayName, null));
        persist(new ExchangeState(
                state.phase(),
                state.inviteCode(),
                participants,
                state.pairRequests(),
                state.drawGeneration(),
                state.assignments(),
                state.deliveredUserIds(),
                state.confirmedUserIds()));
        return RegistrationResult.REGISTERED;
    }

    synchronized InviteStatus inviteStatus(String inviteCode) {
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            return InviteStatus.CLOSED;
        }
        return matchesInviteCode(state.inviteCode(), inviteCode)
                ? InviteStatus.VALID
                : InviteStatus.INVALID;
    }

    synchronized List<SecretSantaBot.Participant> participants() {
        return state.participants().stream()
                .map(ExchangeService::toBotParticipant)
                .toList();
    }

    synchronized Optional<SecretSantaBot.Participant> participant(long userId) {
        return state.participants().stream()
                .filter(participant -> participant.userId() == userId)
                .map(ExchangeService::toBotParticipant)
                .findFirst();
    }

    synchronized PairingChange pair(long firstId, long secondId) throws IOException {
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            throw new IllegalStateException("Pairing is available only during registration.");
        }
        if (firstId == secondId) {
            throw new IllegalArgumentException("A participant cannot be paired with themselves.");
        }
        ExchangeState.Participant first = findParticipant(firstId);
        ExchangeState.Participant second = findParticipant(secondId);
        if (first == null || second == null) {
            throw new IllegalArgumentException("Both participants must be registered.");
        }
        if (Objects.equals(first.partnerId(), secondId)
                && Objects.equals(second.partnerId(), firstId)) {
            return new PairingChange(
                    toBotParticipant(first), toBotParticipant(second), null, null, true);
        }

        ExchangeState.Participant formerPartnerOfFirst = findParticipant(first.partnerId());
        ExchangeState.Participant formerPartnerOfSecond = findParticipant(second.partnerId());
        Map<Long, ExchangeState.Participant> updatedParticipants = new LinkedHashMap<>();
        state.participants().forEach(participant ->
                updatedParticipants.put(participant.userId(), participant));
        clearPartner(updatedParticipants, first.partnerId(), firstId);
        clearPartner(updatedParticipants, second.partnerId(), secondId);
        updatedParticipants.put(
                firstId, new ExchangeState.Participant(firstId, first.name(), secondId));
        updatedParticipants.put(
                secondId, new ExchangeState.Participant(secondId, second.name(), firstId));

        List<ExchangeState.PairRequest> remainingRequests = state.pairRequests().stream()
                .filter(request -> request.requesterId() != firstId
                        && request.requestedPartnerId() != firstId
                        && request.requesterId() != secondId
                        && request.requestedPartnerId() != secondId)
                .toList();
        persist(new ExchangeState(
                state.phase(),
                state.inviteCode(),
                List.copyOf(updatedParticipants.values()),
                remainingRequests,
                state.drawGeneration(),
                state.assignments(),
                state.deliveredUserIds(),
                state.confirmedUserIds()));

        return new PairingChange(
                toBotParticipant(updatedParticipants.get(firstId)),
                toBotParticipant(updatedParticipants.get(secondId)),
                formerPartnerOfFirst == null ? null : toBotParticipant(formerPartnerOfFirst),
                formerPartnerOfSecond == null ? null : toBotParticipant(formerPartnerOfSecond),
                false);
    }

    synchronized void clearParticipants() throws IOException {
        if (state.phase() == ExchangeState.Phase.EMPTY) {
            return;
        }
        if (state.phase() != ExchangeState.Phase.REGISTRATION_CLOSED) {
            throw new IllegalStateException("An active exchange cannot be cleared.");
        }
        persist(ExchangeState.empty());
    }

    synchronized void closeRegistration() throws IOException {
        if (state.phase() == ExchangeState.Phase.REGISTRATION_OPEN) {
            persist(new ExchangeState(
                    ExchangeState.Phase.REGISTRATION_CLOSED,
                    null,
                    state.participants(),
                    List.of(),
                    0,
                    List.of(),
                    Set.of(),
                    Set.of()));
        }
        organizerService.closeRegistration();
    }

    synchronized ExchangeState state() {
        return state;
    }

    private ExchangeState.Participant findParticipant(Long userId) {
        if (userId == null) {
            return null;
        }
        return state.participants().stream()
                .filter(participant -> participant.userId() == userId)
                .findFirst()
                .orElse(null);
    }

    private static void clearPartner(
            Map<Long, ExchangeState.Participant> participants,
            Long formerPartnerId,
            long participantId) {
        if (formerPartnerId == null) {
            return;
        }
        ExchangeState.Participant formerPartner = participants.get(formerPartnerId);
        if (formerPartner != null && Objects.equals(formerPartner.partnerId(), participantId)) {
            participants.put(
                    formerPartnerId,
                    new ExchangeState.Participant(
                            formerPartner.userId(), formerPartner.name(), null));
        }
    }

    private static SecretSantaBot.Participant toBotParticipant(
            ExchangeState.Participant participant) {
        return new SecretSantaBot.Participant(
                participant.userId(), participant.name(), participant.partnerId());
    }

    private void persist(ExchangeState nextState) throws IOException {
        store.save(nextState);
        state = nextState;
    }

    private static boolean matchesInviteCode(String expectedCode, String providedCode) {
        byte[] expected = expectedCode.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedCode == null
                ? new byte[0]
                : providedCode.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
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

    static final class ExchangeInProgressException extends IllegalStateException {
        ExchangeInProgressException() {
            super("An exchange must be reset or completed before opening another.");
        }
    }
}
