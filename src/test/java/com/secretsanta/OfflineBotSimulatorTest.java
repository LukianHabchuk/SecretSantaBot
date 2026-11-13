package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineBotSimulatorTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void startCommandRunsAgainstLocalFakeMessenger() throws Exception {
        String input = "fail 201\nrecover 201\nas 100 /start\nclick 2\nclick 1\nquit\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        OfflineBotSimulator.runSession(
                new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                dataDirectory);

        String transcript = output.toString(StandardCharsets.UTF_8);
        assertTrue(transcript.contains("Таємний Санта"));
        assertTrue(transcript.contains("[offline]"));
        assertTrue(transcript.contains("Simulated delivery failure for user 201."));
        assertTrue(transcript.contains("Simulated delivery restored for user 201."));
        assertTrue(transcript.contains("[keyboard 1 for user 100]"));
        assertTrue(transcript.contains("Введи код налаштування"));
    }

    @Test
    void completesMixedLanguageExchangeAndRetainsPreferencesAfterCleanup() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OfflineBotSimulator.Session session = OfflineBotSimulator.createSession(
                dataDirectory, KEY, new PrintStream(output, true, StandardCharsets.UTF_8));
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Українська");
        session.clickButton(100L, "Я організатор");
        session.sendAs(100L, OfflineBotSimulator.DEMO_SETUP_CODE);
        session.clickButton(100L, "Відкрити реєстрацію");
        String invite = inviteCode(session.latestMessageContaining(100L, "Запрошувальний код:"));

        session.sendAs(201L, "/start");
        session.clickButton(201L, "English");
        session.clickButton(201L, "Join with a code");
        session.sendAs(201L, invite);
        session.clickButton(201L, "Edit name");
        session.sendAs(201L, "Alex");
        session.clickButton(201L, "Confirm name");
        join(session, 202L, invite, "Sam");
        join(session, 203L, invite, "Jamie");

        session.sendAs(201L, "/start");
        session.clickButton(201L, "Request a partner");
        session.clickButton(201L, "Sam");
        session.clickButton(202L, "Підтвердити пару");
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Провести жеребкування");
        assertTrue(session.latestMessageContaining(201L, "Your assignment").contains("Secret Santa"));
        assertTrue(session.latestMessageContaining(202L, "Твоя ціль").contains("Санти"));

        session.clickButton(201L, "I received my assignment");
        for (long userId : List.of(100L, 202L, 203L)) {
            session.clickButton(userId, "Отримав подарункове завдання");
        }
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Очистити обмін");
        session.clickButton(100L, "Так, видалити обмін");
        assertEquals(ExchangeState.Phase.EMPTY, exchangeState().phase());
        assertEquals(BotLanguage.ENGLISH,
                new LanguageStore(dataDirectory, KEY).get(201L).orElseThrow());
        session.sendAs(201L, "/forgetlanguage");
        assertTrue(new LanguageStore(dataDirectory, KEY).get(201L).isEmpty());
    }

    @Test
    void completesPrivateExchangeWalkthroughIncludingRetryRedrawAndCleanup() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream transcript = new PrintStream(output, true, StandardCharsets.UTF_8);
        OfflineBotSimulator.Session session =
                OfflineBotSimulator.createSession(dataDirectory, KEY, transcript);

        session.sendAs(100L, "/start");
        session.clickButton(100L, "Українська");
        session.clickButton(100L, "Я організатор");
        session.sendAs(100L, OfflineBotSimulator.DEMO_SETUP_CODE);
        session.clickButton(100L, "Відкрити реєстрацію");
        assertTrue(
                output.toString(StandardCharsets.UTF_8).contains("Запрошувальний код:"),
                output.toString(StandardCharsets.UTF_8));
        String inviteCode = inviteCode(session.latestMessageContaining(
                100L, "Запрошувальний код:"));

        join(session, 201L, inviteCode, "Alex");
        join(session, 202L, inviteCode, "Sam");
        join(session, 203L, inviteCode, "Jamie");

        session.sendAs(201L, "/start");
        session.clickButton(201L, "Запросити пару");
        session.clickButton(201L, "Sam");
        session.clickButton(202L, "Підтвердити пару");

        session.failDeliveryTo(203L);
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Провести жеребкування");
        assertEquals(
                ExchangeState.Phase.DELIVERING,
                exchangeState().phase());

        session.recoverDeliveryTo(203L);
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Повторити доставку");
        ExchangeState firstDraw = exchangeState();
        assertEquals(ExchangeState.Phase.AWAITING_CONFIRMATIONS, firstDraw.phase());
        assertEquals(1, firstDraw.drawGeneration());

        List<Long> exchangeUserIds = List.of(100L, 201L, 202L, 203L);
        for (long userId : exchangeUserIds) {
            session.clickButton(userId, "Отримав подарункове завдання");
        }
        session.sendAs(100L, "/start");
        session.clickButton(100L, "Провести нове жеребкування");
        session.clickButton(100L, "Так, замінити розподіл");

        ExchangeState replacement = exchangeState();
        assertEquals(2, replacement.drawGeneration());
        assertTrue(replacement.confirmedUserIds().isEmpty());
        for (long userId : exchangeUserIds) {
            session.clickButton(userId, "Отримав подарункове завдання");
        }

        session.sendAs(100L, "/start");
        session.clickButton(100L, "Очистити обмін");
        session.clickButton(100L, "Так, видалити обмін");

        assertEquals(ExchangeState.Phase.EMPTY, exchangeState().phase());
        assertTrue(new OrganizerService(
                        new OrganizerStore(dataDirectory), OfflineBotSimulator.DEMO_SETUP_CODE)
                .isOrganizer(100L));
        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("offline callback"));
    }

    private void join(
            OfflineBotSimulator.Session session, long userId, String inviteCode, String name) {
        session.sendAs(userId, "/start");
        session.clickButton(userId, "Українська");
        session.clickButton(userId, "Приєднатися за кодом");
        session.sendAs(userId, inviteCode);
        session.clickButton(userId, "Змінити ім'я");
        session.sendAs(userId, name);
        session.clickButton(userId, "Підтвердити ім'я");
    }

    private ExchangeState exchangeState() throws Exception {
        return new ExchangeStore(dataDirectory, KEY).load().orElseThrow();
    }

    private static String inviteCode(String message) {
        String marker = "Запрошувальний код: `";
        int start = message.indexOf(marker);
        assertTrue(start >= 0, message);
        int codeStart = start + marker.length();
        int codeEnd = message.indexOf('`', codeStart);
        assertTrue(codeEnd > codeStart, message);
        return message.substring(codeStart, codeEnd);
    }
}
