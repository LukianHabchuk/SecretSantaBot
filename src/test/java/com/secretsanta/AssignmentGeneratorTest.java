package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AssignmentGeneratorTest {
    @Test
    void createsCompleteAssignmentsWithoutSelfOrPartnerDraws() {
        List<SecretSantaBot.Participant> participants = List.of(
                new SecretSantaBot.Participant(1L, "Alex", 2L),
                new SecretSantaBot.Participant(2L, "Sam", 1L),
                new SecretSantaBot.Participant(3L, "Jo", 4L),
                new SecretSantaBot.Participant(4L, "Kim", 3L),
                new SecretSantaBot.Participant(5L, "Lee", null));

        for (int attempt = 0; attempt < 20; attempt++) {
            Map<SecretSantaBot.Participant, SecretSantaBot.Participant> assignments =
                    AssignmentGenerator.generate(participants);

            assertEquals(Set.copyOf(participants), assignments.keySet());
            assertEquals(participants.size(), new HashSet<>(assignments.values()).size());
            assertEquals(Set.copyOf(participants), new HashSet<>(assignments.values()));
            assignments.forEach((giver, recipient) -> {
                assertNotEquals(giver, recipient);
                assertFalse(giver.partnerId() != null
                        && giver.partnerId().equals(recipient.chatId()));
            });
        }
    }

    @Test
    void rejectsAnImpossiblePartnerConfiguration() {
        List<SecretSantaBot.Participant> participants = List.of(
                new SecretSantaBot.Participant(1L, "Alex", 2L),
                new SecretSantaBot.Participant(2L, "Sam", 1L));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AssignmentGenerator.generate(participants));

        assertTrue(exception.getMessage().contains("valid assignment"));
    }

    @Test
    void rejectsGroupsWithFewerThanTwoParticipants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AssignmentGenerator.generate(List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> AssignmentGenerator.generate(List.of(
                        new SecretSantaBot.Participant(1L, "Alex", null))));
    }

    @Test
    void rejectsDuplicateTelegramParticipantIds() {
        List<SecretSantaBot.Participant> participants = List.of(
                new SecretSantaBot.Participant(1L, "Alex", null),
                new SecretSantaBot.Participant(1L, "Sam", null));

        assertThrows(
                IllegalArgumentException.class,
                () -> AssignmentGenerator.generate(participants));
    }
}
