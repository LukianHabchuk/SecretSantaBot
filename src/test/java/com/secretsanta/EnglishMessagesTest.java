package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class EnglishMessagesTest {
    @Test
    void translatesEveryExistingStaticBotMessage() throws Exception {
        for (Field field : BotMessages.class.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())) {
                String original = (String) field.get(null);
                assertNotNull(EnglishMessages.translate(original), field.getName());
            }
        }
    }

    @Test
    void translatesOnboardingAndAuthorizationMessages() {
        assertTrue(EnglishMessages.translate(BotMessages.PARTICIPANT_JOIN_START)
                .contains("Join"));
        assertTrue(EnglishMessages.translate(BotMessages.ORGANIZER_ONLY_ACTION)
                .contains("organizer"));
    }
}
