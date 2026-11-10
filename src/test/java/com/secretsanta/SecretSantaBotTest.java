package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretSantaBotTest {
    @Test
    void participantRetainsRegistrationDetails() {
        SecretSantaBot.Participant participant =
                new SecretSantaBot.Participant(123L, "Alex", "pair-1");

        assertEquals(123L, participant.chatId());
        assertEquals("Alex", participant.name());
        assertEquals("pair-1", participant.coupleId());
    }
}
