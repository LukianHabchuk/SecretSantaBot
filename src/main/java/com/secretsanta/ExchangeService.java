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

    enum NameUpdateResult {
        UPDATED,
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
            List<ExchangeState.PairRequest> canceledRequests,
            boolean unchanged) {
        PairingChange {
            canceledRequests = List.copyOf(canceledRequests);
        }
    }

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

    synchronized NameUpdateResult updateParticipantName(
            long actorId, long participantId, String name) throws IOException {
        if (actorId <= 0 || participantId <= 0) {
            throw new IllegalArgumentException("Telegram user IDs must be positive.");
        }
        if (actorId != participantId && !organizerService.isOrganizer(actorId)) {
            throw new SecurityException("Only a participant or the organizer can edit a name.");
        }
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            throw new IllegalStateException("Names can only be edited before the draw.");
        }

        String displayName = validDisplayName(name);
        if (displayName == null) {
            return NameUpdateResult.INVALID_NAME;
        }

        List<ExchangeState.Participant> participants = new ArrayList<>(state.participants());
        int participantIndex = -1;
        for (int index = 0; index < participants.size(); index++) {
            if (participants.get(index).userId() == participantId) {
                participantIndex = index;
                break;
            }
        }
        if (participantIndex < 0) {
            throw new IllegalArgumentException("Participant is not registered.");
        }

        ExchangeState.Participant current = participants.get(participantIndex);
        if (current.name().equals(displayName)) {
            return NameUpdateResult.UPDATED;
        }
        participants.set(
                participantIndex,
                new ExchangeState.Participant(
                        current.userId(), displayName, current.partnerId()));
        persist(new ExchangeState(
                state.phase(),
                state.inviteCode(),
                participants,
                state.pairRequests(),
                state.drawGeneration(),
                state.assignments(),
                state.deliveredUserIds(),
                state.confirmedUserIds()));
        return NameUpdateResult.UPDATED;
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

    synchronized void requestPair(long requesterId, long requestedPartnerId) throws IOException {
        requireRegistrationOpen();
        if (requesterId == requestedPartnerId) {
            throw new IllegalArgumentException("A participant cannot request themselves.");
        }
        ExchangeState.Participant requester = findParticipant(requesterId);
        ExchangeState.Participant requestedPartner = findParticipant(requestedPartnerId);
        if (requester == null || requestedPartner == null) {
            throw new IllegalArgumentException("Both participants must be registered.");
        }
        if (requester.partnerId() != null || requestedPartner.partnerId() != null) {
            throw new IllegalArgumentException("Paired participants cannot request a partner.");
        }
        if (state.pairRequests().stream().anyMatch(request ->
                involves(request, requesterId) || involves(request, requestedPartnerId))) {
            throw new IllegalArgumentException(
                    "A participant can have only one open pair request.");
        }

        List<ExchangeState.PairRequest> requests = new ArrayList<>(state.pairRequests());
        requests.add(new ExchangeState.PairRequest(requesterId, requestedPartnerId));
        persist(stateWithPairRequests(requests));
    }

    synchronized void acceptPairRequest(long recipientId, long requesterId) throws IOException {
        requireRegistrationOpen();
        ExchangeState.PairRequest request = requirePairRequest(requesterId, recipientId);
        Map<Long, ExchangeState.Participant> participants = new LinkedHashMap<>();
        state.participants().forEach(participant ->
                participants.put(participant.userId(), participant));
        ExchangeState.Participant requester = participants.get(requesterId);
        ExchangeState.Participant recipient = participants.get(recipientId);
        participants.put(
                requesterId,
                new ExchangeState.Participant(requesterId, requester.name(), recipientId));
        participants.put(
                recipientId,
                new ExchangeState.Participant(recipientId, recipient.name(), requesterId));

        List<ExchangeState.PairRequest> requests = state.pairRequests().stream()
                .filter(pending -> !pending.equals(request))
                .toList();
        persist(stateWithParticipantsAndRequests(participants, requests));
    }

    synchronized void declinePairRequest(long recipientId, long requesterId) throws IOException {
        requireRegistrationOpen();
        removePairRequest(recipientId, requesterId, recipientId);
    }

    synchronized void cancelPairRequest(long requesterId, long requestedPartnerId)
            throws IOException {
        requireRegistrationOpen();
        removePairRequest(requesterId, requesterId, requestedPartnerId);
    }

    synchronized boolean hasOpenPairRequests() {
        return !state.pairRequests().isEmpty();
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
                    toBotParticipant(first), toBotParticipant(second),
                    null, null, List.of(), true);
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

        List<ExchangeState.PairRequest> canceledRequests = state.pairRequests().stream()
                .filter(request -> involves(request, firstId) || involves(request, secondId))
                .toList();
        List<ExchangeState.PairRequest> remainingRequests = state.pairRequests().stream()
                .filter(request -> !involves(request, firstId) && !involves(request, secondId))
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
                canceledRequests,
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

    synchronized void beginDraw(List<ExchangeState.Assignment> assignments) throws IOException {
        requireRegistrationOpen();
        if (!state.pairRequests().isEmpty()) {
            throw new IllegalStateException(
                    "Pair requests must be resolved before assignment.");
        }
        ExchangeState nextState = new ExchangeState(
                ExchangeState.Phase.DELIVERING,
                null,
                state.participants(),
                List.of(),
                state.drawGeneration() + 1,
                assignments,
                Set.of(),
                Set.of());
        persist(nextState);
        organizerService.closeRegistration();
    }

    synchronized void replaceDraw(List<ExchangeState.Assignment> assignments)
            throws IOException {
        if (!canReplaceDraw()) {
            throw new IllegalStateException(
                    "A replacement draw requires every current assignment to be delivered.");
        }
        persist(new ExchangeState(
                ExchangeState.Phase.DELIVERING,
                null,
                state.participants(),
                List.of(),
                state.drawGeneration() + 1,
                assignments,
                Set.of(),
                Set.of()));
    }

    synchronized boolean canReplaceDraw() {
        Set<Long> giverIds = state.assignments().stream()
                .map(ExchangeState.Assignment::giverId)
                .collect(java.util.stream.Collectors.toSet());
        return state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS
                && state.deliveredUserIds().equals(giverIds);
    }

    synchronized void markAssignmentDelivered(long giverId) throws IOException {
        if (state.phase() != ExchangeState.Phase.DELIVERING
                || state.assignments().stream().noneMatch(
                        assignment -> assignment.giverId() == giverId)) {
            throw new IllegalArgumentException("Assignment delivery is not pending.");
        }
        if (state.deliveredUserIds().contains(giverId)) {
            return;
        }

        Set<Long> deliveredUserIds = new java.util.HashSet<>(state.deliveredUserIds());
        deliveredUserIds.add(giverId);
        Set<Long> giverIds = state.assignments().stream()
                .map(ExchangeState.Assignment::giverId)
                .collect(java.util.stream.Collectors.toSet());
        ExchangeState.Phase phase = deliveredUserIds.equals(giverIds)
                ? ExchangeState.Phase.AWAITING_CONFIRMATIONS
                : ExchangeState.Phase.DELIVERING;
        persist(new ExchangeState(
                phase,
                null,
                state.participants(),
                List.of(),
                state.drawGeneration(),
                state.assignments(),
                deliveredUserIds,
                state.confirmedUserIds()));
    }

    synchronized boolean confirmReceipt(long participantId, long drawGeneration)
            throws IOException {
        if (state.phase() != ExchangeState.Phase.AWAITING_CONFIRMATIONS
                || state.drawGeneration() != drawGeneration) {
            throw new IllegalStateException("Receipt confirmation is not available for this draw.");
        }
        requireRegisteredParticipant(participantId);
        if (state.confirmedUserIds().contains(participantId)) {
            return false;
        }

        Set<Long> confirmedUserIds = new java.util.HashSet<>(state.confirmedUserIds());
        confirmedUserIds.add(participantId);
        persist(new ExchangeState(
                state.phase(),
                null,
                state.participants(),
                List.of(),
                state.drawGeneration(),
                state.assignments(),
                state.deliveredUserIds(),
                confirmedUserIds));
        return true;
    }

    synchronized boolean cleanupExchange(long organizerId, boolean confirmed)
            throws IOException {
        if (!organizerService.isOrganizer(organizerId)) {
            throw new SecurityException("Only the organizer can clean up an exchange.");
        }
        if (!confirmed) {
            return false;
        }
        Set<Long> participantIds = state.participants().stream()
                .map(ExchangeState.Participant::userId)
                .collect(java.util.stream.Collectors.toSet());
        if (state.phase() != ExchangeState.Phase.AWAITING_CONFIRMATIONS
                || !state.confirmedUserIds().equals(participantIds)) {
            throw new IllegalStateException(
                    "Every participant must confirm receipt before cleanup.");
        }
        persist(ExchangeState.empty());
        return true;
    }

    synchronized boolean canCleanupExchange() {
        Set<Long> participantIds = state.participants().stream()
                .map(ExchangeState.Participant::userId)
                .collect(java.util.stream.Collectors.toSet());
        return state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS
                && state.confirmedUserIds().equals(participantIds);
    }

    synchronized boolean hasActiveDraw() {
        return state.phase() == ExchangeState.Phase.DELIVERING
                || state.phase() == ExchangeState.Phase.AWAITING_CONFIRMATIONS;
    }

    synchronized void migrateLegacyDraw(PendingDrawStore.PendingDraw legacyDraw)
            throws IOException {
        Map<Long, String> legacyNames = new LinkedHashMap<>();
        List<ExchangeState.Assignment> assignments = new ArrayList<>();
        for (PendingDrawStore.Assignment assignment : legacyDraw.assignments()) {
            addLegacyName(legacyNames, assignment.giverId(), assignment.giverName());
            addLegacyName(legacyNames, assignment.receiverId(), assignment.receiverName());
            assignments.add(new ExchangeState.Assignment(
                    assignment.giverId(), assignment.receiverId()));
        }
        Set<Long> deliveredUserIds = legacyDraw.deliveredGiverIds();

        if (hasActiveDraw()) {
            Map<Long, String> persistedNames = new LinkedHashMap<>();
            state.participants().forEach(participant ->
                    persistedNames.put(participant.userId(), participant.name()));
            if (!state.assignments().equals(assignments)
                    || !state.deliveredUserIds().equals(deliveredUserIds)
                    || !persistedNames.equals(legacyNames)) {
                throw new IOException("Legacy draw conflicts with persisted exchange state.");
            }
            verifyPersistedState(state);
            return;
        }
        if (state.phase() != ExchangeState.Phase.EMPTY
                && state.phase() != ExchangeState.Phase.REGISTRATION_OPEN
                && state.phase() != ExchangeState.Phase.REGISTRATION_CLOSED) {
            throw new IOException("Legacy draw cannot be migrated into the current exchange.");
        }
        if (!state.pairRequests().isEmpty()) {
            throw new IOException("Legacy draw conflicts with unresolved pair requests.");
        }

        List<ExchangeState.Participant> participants = state.participants().isEmpty()
                ? legacyNames.entrySet().stream()
                        .map(entry -> new ExchangeState.Participant(
                                entry.getKey(), entry.getValue(), null))
                        .toList()
                : state.participants();
        Map<Long, String> currentNames = new LinkedHashMap<>();
        participants.forEach(participant ->
                currentNames.put(participant.userId(), participant.name()));
        if (!currentNames.equals(legacyNames)) {
            throw new IOException("Legacy draw participants conflict with persisted roster.");
        }

        Set<Long> giverIds = assignments.stream()
                .map(ExchangeState.Assignment::giverId)
                .collect(java.util.stream.Collectors.toSet());
        ExchangeState.Phase phase = deliveredUserIds.equals(giverIds)
                ? ExchangeState.Phase.AWAITING_CONFIRMATIONS
                : ExchangeState.Phase.DELIVERING;
        try {
            ExchangeState migratedState = new ExchangeState(
                    phase,
                    null,
                    participants,
                    List.of(),
                    Math.max(1, state.drawGeneration() + 1),
                    assignments,
                    deliveredUserIds,
                    Set.of());
            persist(migratedState);
            verifyPersistedState(migratedState);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Legacy draw data is invalid for the persisted roster.", exception);
        }
    }

    synchronized void closeRegistration() throws IOException {
        if (state.phase() == ExchangeState.Phase.REGISTRATION_OPEN) {
            if (!state.pairRequests().isEmpty()) {
                throw new IllegalStateException(
                        "Pair requests must be resolved before closing registration.");
            }
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

    private void removePairRequest(
            long actorId, long requesterId, long requestedPartnerId) throws IOException {
        requireRegisteredParticipant(actorId);
        requireRegisteredParticipant(requesterId);
        requireRegisteredParticipant(requestedPartnerId);
        if (actorId != requesterId && actorId != requestedPartnerId) {
            throw new IllegalArgumentException("Only a request participant can resolve it.");
        }
        ExchangeState.PairRequest request = requirePairRequest(requesterId, requestedPartnerId);
        List<ExchangeState.PairRequest> requests = state.pairRequests().stream()
                .filter(pending -> !pending.equals(request))
                .toList();
        persist(stateWithPairRequests(requests));
    }

    private ExchangeState.PairRequest requirePairRequest(long requesterId, long recipientId) {
        requireRegisteredParticipant(requesterId);
        requireRegisteredParticipant(recipientId);
        return state.pairRequests().stream()
                .filter(request -> request.requesterId() == requesterId
                        && request.requestedPartnerId() == recipientId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Pair request does not exist."));
    }

    private void requireRegisteredParticipant(long userId) {
        if (findParticipant(userId) == null) {
            throw new IllegalArgumentException("Participant is not registered.");
        }
    }

    private void requireRegistrationOpen() {
        if (state.phase() != ExchangeState.Phase.REGISTRATION_OPEN) {
            throw new IllegalStateException("Pair requests are available only during registration.");
        }
    }

    private boolean involves(ExchangeState.PairRequest request, long participantId) {
        return request.requesterId() == participantId
                || request.requestedPartnerId() == participantId;
    }

    private ExchangeState stateWithPairRequests(List<ExchangeState.PairRequest> requests) {
        return stateWithParticipantsAndRequests(state.participants(), requests);
    }

    private ExchangeState stateWithParticipantsAndRequests(
            List<ExchangeState.Participant> participants,
            List<ExchangeState.PairRequest> requests) {
        return new ExchangeState(
                state.phase(),
                state.inviteCode(),
                participants,
                requests,
                state.drawGeneration(),
                state.assignments(),
                state.deliveredUserIds(),
                state.confirmedUserIds());
    }

    private ExchangeState stateWithParticipantsAndRequests(
            Map<Long, ExchangeState.Participant> participants,
            List<ExchangeState.PairRequest> requests) {
        return stateWithParticipantsAndRequests(List.copyOf(participants.values()), requests);
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

    private void verifyPersistedState(ExchangeState expected) throws IOException {
        if (!store.load().filter(expected::equals).isPresent()) {
            throw new IOException("Persisted exchange state verification failed.");
        }
    }

    private static void addLegacyName(Map<Long, String> names, long userId, String name)
            throws IOException {
        String existing = names.putIfAbsent(userId, name);
        if (existing != null && !existing.equals(name)) {
            throw new IOException("Legacy draw contains conflicting participant names.");
        }
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
