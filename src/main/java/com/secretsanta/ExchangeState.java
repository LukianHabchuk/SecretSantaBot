package com.secretsanta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

record ExchangeState(
        Phase phase,
        String inviteCode,
        List<Participant> participants,
        List<PairRequest> pairRequests,
        long drawGeneration,
        List<Assignment> assignments,
        Set<Long> deliveredUserIds,
        Set<Long> confirmedUserIds) {
    private static final int MAX_PARTICIPANTS = 10_000;
    private static final int MAX_NAME_CODE_POINTS = 64;
    private static final int MAX_INVITE_CODE_CODE_POINTS = 256;

    enum Phase {
        EMPTY,
        REGISTRATION_OPEN,
        DELIVERING,
        AWAITING_CONFIRMATIONS
    }

    record Participant(long userId, String name, Long partnerId) {
        Participant {
            if (userId <= 0) {
                throw new IllegalArgumentException("Participant ID must be positive.");
            }
            if (name == null) {
                throw new IllegalArgumentException("Participant name is required.");
            }
            name = name.strip();
            if (name.isBlank()
                    || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Participant name is invalid.");
            }
            if (partnerId != null && (partnerId <= 0 || partnerId == userId)) {
                throw new IllegalArgumentException("Participant partner ID is invalid.");
            }
        }
    }

    record PairRequest(long requesterId, long requestedPartnerId) {
        PairRequest {
            if (requesterId <= 0
                    || requestedPartnerId <= 0
                    || requesterId == requestedPartnerId) {
                throw new IllegalArgumentException("Pair request participants are invalid.");
            }
        }
    }

    record Assignment(long giverId, long receiverId) {
        Assignment {
            if (giverId <= 0 || receiverId <= 0 || giverId == receiverId) {
                throw new IllegalArgumentException("Assignment participants are invalid.");
            }
        }
    }

    ExchangeState {
        Objects.requireNonNull(phase, "Exchange phase is required.");
        participants = List.copyOf(Objects.requireNonNull(
                participants, "Participants are required."));
        pairRequests = List.copyOf(Objects.requireNonNull(
                pairRequests, "Pair requests are required."));
        assignments = List.copyOf(Objects.requireNonNull(
                assignments, "Assignments are required."));
        deliveredUserIds = Set.copyOf(Objects.requireNonNull(
                deliveredUserIds, "Delivery progress is required."));
        confirmedUserIds = Set.copyOf(Objects.requireNonNull(
                confirmedUserIds, "Receipt confirmations are required."));
        if (phase == Phase.REGISTRATION_OPEN && inviteCode != null) {
            inviteCode = inviteCode.strip();
        }

        if (participants.size() > MAX_PARTICIPANTS
                || pairRequests.size() > MAX_PARTICIPANTS
                || assignments.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("Exchange state is too large.");
        }
        if (drawGeneration < 0) {
            throw new IllegalArgumentException("Draw generation cannot be negative.");
        }

        Map<Long, Participant> participantsById = indexParticipants(participants);
        validatePairs(participants, participantsById);
        validateRequests(phase, pairRequests, participantsById);
        validatePhase(
                phase,
                inviteCode,
                participants,
                pairRequests,
                drawGeneration,
                assignments,
                deliveredUserIds,
                confirmedUserIds,
                participantsById);
    }

    static ExchangeState empty() {
        return new ExchangeState(
                Phase.EMPTY, null, List.of(), List.of(), 0, List.of(), Set.of(), Set.of());
    }

    private static Map<Long, Participant> indexParticipants(List<Participant> participants) {
        Map<Long, Participant> participantsById = new HashMap<>();
        for (Participant participant : participants) {
            if (participant == null
                    || participantsById.putIfAbsent(participant.userId(), participant) != null) {
                throw new IllegalArgumentException("Exchange participants are invalid.");
            }
        }
        return participantsById;
    }

    private static void validatePairs(
            List<Participant> participants, Map<Long, Participant> participantsById) {
        for (Participant participant : participants) {
            if (participant.partnerId() == null) {
                continue;
            }
            Participant partner = participantsById.get(participant.partnerId());
            if (partner == null || !Objects.equals(partner.partnerId(), participant.userId())) {
                throw new IllegalArgumentException("Participant pairs must be reciprocal.");
            }
        }
    }

    private static void validateRequests(
            Phase phase,
            List<PairRequest> pairRequests,
            Map<Long, Participant> participantsById) {
        if (phase != Phase.REGISTRATION_OPEN && !pairRequests.isEmpty()) {
            throw new IllegalArgumentException("Pair requests are only valid during registration.");
        }
        Set<Long> participantsWithOpenRequests = new HashSet<>();
        for (PairRequest request : pairRequests) {
            if (request == null) {
                throw new IllegalArgumentException("Pair requests are invalid.");
            }
            Participant requester = participantsById.get(request.requesterId());
            Participant requestedPartner = participantsById.get(request.requestedPartnerId());
            if (requester == null
                    || requestedPartner == null
                    || requester.partnerId() != null
                    || requestedPartner.partnerId() != null
                    || !participantsWithOpenRequests.add(request.requesterId())
                    || !participantsWithOpenRequests.add(request.requestedPartnerId())) {
                throw new IllegalArgumentException("Pair request state is invalid.");
            }
        }
    }

    private static void validatePhase(
            Phase phase,
            String inviteCode,
            List<Participant> participants,
            List<PairRequest> pairRequests,
            long drawGeneration,
            List<Assignment> assignments,
            Set<Long> deliveredUserIds,
            Set<Long> confirmedUserIds,
            Map<Long, Participant> participantsById) {
        if (phase == Phase.EMPTY) {
            if (inviteCode != null
                    || !participants.isEmpty()
                    || !pairRequests.isEmpty()
                    || drawGeneration != 0
                    || !assignments.isEmpty()
                    || !deliveredUserIds.isEmpty()
                    || !confirmedUserIds.isEmpty()) {
                throw new IllegalArgumentException("Empty exchange state contains data.");
            }
            return;
        }

        if (phase == Phase.REGISTRATION_OPEN) {
            if (inviteCode == null) {
                throw new IllegalArgumentException("Open registration requires an invite code.");
            }
            inviteCode = inviteCode.strip();
            if (inviteCode.isBlank()
                    || inviteCode.codePointCount(0, inviteCode.length())
                            > MAX_INVITE_CODE_CODE_POINTS
                    || inviteCode.codePoints().anyMatch(Character::isISOControl)
                    || drawGeneration != 0
                    || !assignments.isEmpty()
                    || !deliveredUserIds.isEmpty()
                    || !confirmedUserIds.isEmpty()) {
                throw new IllegalArgumentException("Open registration state is invalid.");
            }
            return;
        }

        if (inviteCode != null || drawGeneration < 1 || !pairRequests.isEmpty()) {
            throw new IllegalArgumentException("Draw state is invalid.");
        }
        validateAssignments(assignments, participantsById);

        Set<Long> giverIds = assignments.stream()
                .map(Assignment::giverId)
                .collect(java.util.stream.Collectors.toSet());
        if (!giverIds.containsAll(deliveredUserIds)
                || !giverIds.containsAll(confirmedUserIds)
                || !deliveredUserIds.containsAll(confirmedUserIds)) {
            throw new IllegalArgumentException("Draw delivery or confirmation state is invalid.");
        }
        if (phase == Phase.AWAITING_CONFIRMATIONS && !deliveredUserIds.equals(giverIds)) {
            throw new IllegalArgumentException(
                    "Confirmations cannot be awaited before all assignments are delivered.");
        }
    }

    private static void validateAssignments(
            List<Assignment> assignments, Map<Long, Participant> participantsById) {
        if (participantsById.size() < 2 || assignments.size() != participantsById.size()) {
            throw new IllegalArgumentException("Draw assignments are incomplete.");
        }

        Set<Long> giverIds = new HashSet<>();
        Set<Long> receiverIds = new HashSet<>();
        for (Assignment assignment : assignments) {
            if (assignment == null) {
                throw new IllegalArgumentException("Draw assignments are invalid.");
            }
            Participant giver = participantsById.get(assignment.giverId());
            Participant receiver = participantsById.get(assignment.receiverId());
            if (giver == null
                    || receiver == null
                    || !giverIds.add(assignment.giverId())
                    || !receiverIds.add(assignment.receiverId())
                    || Objects.equals(giver.partnerId(), assignment.receiverId())) {
                throw new IllegalArgumentException("Draw assignments are invalid.");
            }
        }
        if (!giverIds.equals(participantsById.keySet())
                || !receiverIds.equals(participantsById.keySet())) {
            throw new IllegalArgumentException("Draw assignments are incomplete.");
        }
    }
}
