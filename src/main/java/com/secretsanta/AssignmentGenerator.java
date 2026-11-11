package com.secretsanta;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AssignmentGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();

    private AssignmentGenerator() {}

    static Map<SecretSantaBot.Participant, SecretSantaBot.Participant> generate(
            List<SecretSantaBot.Participant> group) {
        if (group == null || group.size() < 2) {
            throw new IllegalArgumentException("At least two participants are required.");
        }

        List<SecretSantaBot.Participant> givers = new ArrayList<>(group);
        Set<Long> participantIds = new HashSet<>();
        for (SecretSantaBot.Participant participant : givers) {
            if (participant == null) {
                throw new IllegalArgumentException("Participants cannot be null.");
            }
            if (!participantIds.add(participant.chatId())) {
                throw new IllegalArgumentException("Participant IDs must be unique.");
            }
        }

        List<SecretSantaBot.Participant> receivers = new ArrayList<>(givers);
        Collections.shuffle(givers, RANDOM);
        Collections.shuffle(receivers, RANDOM);

        Map<SecretSantaBot.Participant, SecretSantaBot.Participant> receiverToGiver =
                new HashMap<>();
        Map<SecretSantaBot.Participant, SecretSantaBot.Participant> giverToReceiver =
                new HashMap<>();

        for (SecretSantaBot.Participant giver : givers) {
            if (!assign(giver, receivers, receiverToGiver, giverToReceiver, new HashSet<>())) {
                throw new IllegalStateException("No valid assignment exists for this group.");
            }
        }

        Map<SecretSantaBot.Participant, SecretSantaBot.Participant> assignments =
                new LinkedHashMap<>();
        for (SecretSantaBot.Participant giver : givers) {
            assignments.put(giver, giverToReceiver.get(giver));
        }
        return Collections.unmodifiableMap(assignments);
    }

    private static boolean assign(
            SecretSantaBot.Participant giver,
            List<SecretSantaBot.Participant> receivers,
            Map<SecretSantaBot.Participant, SecretSantaBot.Participant> receiverToGiver,
            Map<SecretSantaBot.Participant, SecretSantaBot.Participant> giverToReceiver,
            Set<SecretSantaBot.Participant> visitedReceivers) {
        for (SecretSantaBot.Participant receiver : receivers) {
            if (cannotReceiveFrom(giver, receiver) || !visitedReceivers.add(receiver)) {
                continue;
            }

            SecretSantaBot.Participant previousGiver = receiverToGiver.get(receiver);
            if (previousGiver == null || assign(
                    previousGiver, receivers, receiverToGiver, giverToReceiver, visitedReceivers)) {
                receiverToGiver.put(receiver, giver);
                giverToReceiver.put(giver, receiver);
                return true;
            }
        }
        return false;
    }

    private static boolean cannotReceiveFrom(
            SecretSantaBot.Participant giver, SecretSantaBot.Participant receiver) {
        return giver.chatId() == receiver.chatId()
                || (giver.coupleId() != null
                        && giver.coupleId().equals(receiver.coupleId()));
    }
}
