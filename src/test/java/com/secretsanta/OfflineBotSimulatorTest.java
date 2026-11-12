package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineBotSimulatorTest {
    @TempDir
    Path dataDirectory;

    @Test
    void startCommandRunsAgainstLocalFakeMessenger() throws Exception {
        String input = "as 100 /start\nquit\n";
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
    }
}
