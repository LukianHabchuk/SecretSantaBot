package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExchangeStateTest {
    @Test
    void emptyStateContainsNoExchangeData() {
        ExchangeState state = ExchangeState.empty();

        assertEquals(ExchangeState.Phase.EMPTY, state.phase());
        assertTrue(state.participants().isEmpty());
        assertTrue(state.assignments().isEmpty());
        assertTrue(state.deliveredUserIds().isEmpty());
        assertTrue(state.confirmedUserIds().isEmpty());
    }

    @Test
    void lateRegistrationRetainsTheDeliveredDrawForTheOriginalRoster() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Organizer", null),
                new ExchangeState.Participant(102L, "Alex", null),
                new ExchangeState.Participant(103L, "Sam", null),
                new ExchangeState.Participant(104L, "Late friend", null));
        List<ExchangeState.Assignment> previousAssignments = List.of(
                new ExchangeState.Assignment(101L, 102L),
                new ExchangeState.Assignment(102L, 103L),
                new ExchangeState.Assignment(103L, 101L));

        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                "existing-invite",
                participants,
                List.of(),
                1,
                previousAssignments,
                Set.of(101L, 102L, 103L),
                Set.of(101L, 102L));

        assertEquals(ExchangeState.Phase.LATE_REGISTRATION_OPEN, state.phase());
        assertEquals("existing-invite", state.inviteCode());
        assertEquals(4, state.participants().size());
        assertEquals(previousAssignments, state.assignments());
        assertEquals(Set.of(101L, 102L, 103L), state.deliveredUserIds());
        assertEquals(Set.of(101L, 102L), state.confirmedUserIds());
    }

    @Test
    void lateRegistrationRequiresAllPreviousAssignmentsToBeDelivered() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Organizer", null),
                new ExchangeState.Participant(102L, "Alex", null),
                new ExchangeState.Participant(103L, "Sam", null),
                new ExchangeState.Participant(104L, "Late friend", null));
        List<ExchangeState.Assignment> previousAssignments = List.of(
                new ExchangeState.Assignment(101L, 102L),
                new ExchangeState.Assignment(102L, 103L),
                new ExchangeState.Assignment(103L, 101L));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeState(
                        ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                        "existing-invite",
                        participants,
                        List.of(),
                        1,
                        previousAssignments,
                        Set.of(101L, 102L),
                        Set.of(101L)));
    }

    @Test
    void lateParticipantCannotConfirmAnAssignmentFromThePreviousDraw() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Organizer", null),
                new ExchangeState.Participant(102L, "Alex", null),
                new ExchangeState.Participant(103L, "Sam", null),
                new ExchangeState.Participant(104L, "Late friend", null));
        List<ExchangeState.Assignment> previousAssignments = List.of(
                new ExchangeState.Assignment(101L, 102L),
                new ExchangeState.Assignment(102L, 103L),
                new ExchangeState.Assignment(103L, 101L));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeState(
                        ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                        "existing-invite",
                        participants,
                        List.of(),
                        1,
                        previousAssignments,
                        Set.of(101L, 102L, 103L),
                        Set.of(101L, 104L)));
    }

    @Test
    void closedRegistrationRetainsTheRosterWithoutInviteOrDrawData() {
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.REGISTRATION_CLOSED,
                null,
                List.of(new ExchangeState.Participant(101L, "Organizer", null)),
                List.of(),
                0,
                List.of(),
                Set.of(),
                Set.of());

        assertEquals(ExchangeState.Phase.REGISTRATION_CLOSED, state.phase());
        assertEquals(1, state.participants().size());
        assertNull(state.inviteCode());
    }

    @Test
    void registrationStateDefensivelyCopiesCollections() {
        List<ExchangeState.Participant> participants =
                new ArrayList<>(List.of(new ExchangeState.Participant(101L, "Alex", null)));
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.REGISTRATION_OPEN,
                "invite",
                participants,
                List.of(),
                0,
                List.of(),
                Set.of(),
                Set.of());

        participants.clear();

        assertEquals(1, state.participants().size());
        assertThrows(UnsupportedOperationException.class, () -> state.participants().clear());
    }

    @Test
    void drawMustExcludeSelfAndAcceptedPartners() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Alex", 102L),
                new ExchangeState.Participant(102L, "Sam", 101L),
                new ExchangeState.Participant(103L, "Jo", null),
                new ExchangeState.Participant(104L, "Lee", null));

        assertThrows(
                IllegalArgumentException.class,
                () -> deliveringState(
                        participants,
                        List.of(
                                new ExchangeState.Assignment(101L, 102L),
                                new ExchangeState.Assignment(102L, 103L),
                                new ExchangeState.Assignment(103L, 104L),
                                new ExchangeState.Assignment(104L, 101L))));
    }

    @Test
    void drawMustHaveEveryParticipantAsOneGiverAndOneRecipient() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Alex", null),
                new ExchangeState.Participant(102L, "Sam", null),
                new ExchangeState.Participant(103L, "Jo", null));

        assertThrows(
                IllegalArgumentException.class,
                () -> deliveringState(
                        participants,
                        List.of(
                                new ExchangeState.Assignment(101L, 102L),
                                new ExchangeState.Assignment(101L, 103L),
                                new ExchangeState.Assignment(103L, 101L))));
    }

    @Test
    void participantPairsMustBeReciprocal() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeState(
                        ExchangeState.Phase.REGISTRATION_OPEN,
                        "invite",
                        List.of(
                                new ExchangeState.Participant(101L, "Alex", 102L),
                                new ExchangeState.Participant(102L, "Sam", null)),
                        List.of(),
                        0,
                        List.of(),
                        Set.of(),
                        Set.of()));
    }

    @Test
    void participantCanAppearInAtMostOneOpenPairRequest() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeState(
                        ExchangeState.Phase.REGISTRATION_OPEN,
                        "invite",
                        List.of(
                                new ExchangeState.Participant(101L, "Alex", null),
                                new ExchangeState.Participant(102L, "Sam", null),
                                new ExchangeState.Participant(103L, "Jo", null)),
                        List.of(
                                new ExchangeState.PairRequest(101L, 102L),
                                new ExchangeState.PairRequest(103L, 101L)),
                        0,
                        List.of(),
                        Set.of(),
                        Set.of()));
    }

    @Test
    void confirmationsCannotBeAwaitedUntilAllAssignmentsAreDelivered() {
        List<ExchangeState.Participant> participants = List.of(
                new ExchangeState.Participant(101L, "Alex", null),
                new ExchangeState.Participant(102L, "Sam", null),
                new ExchangeState.Participant(103L, "Jo", null));
        List<ExchangeState.Assignment> assignments = List.of(
                new ExchangeState.Assignment(101L, 102L),
                new ExchangeState.Assignment(102L, 103L),
                new ExchangeState.Assignment(103L, 101L));

        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeState(
                        ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                        null,
                        participants,
                        List.of(),
                        1,
                        assignments,
                        Set.of(101L, 102L),
                        Set.of(101L)));
    }

    private static ExchangeState deliveringState(
            List<ExchangeState.Participant> participants,
            List<ExchangeState.Assignment> assignments) {
        return new ExchangeState(
                ExchangeState.Phase.DELIVERING,
                null,
                participants,
                List.of(),
                1,
                assignments,
                Set.of(),
                Set.of());
    }
}
