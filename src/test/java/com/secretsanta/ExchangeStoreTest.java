package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExchangeStoreTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void encryptsAndReloadsTheWholeExchangeState() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState state = sampleState();

        store.save(state);
        byte[] encrypted = Files.readAllBytes(store.stateFile());

        assertFalse(new String(encrypted).contains("Alex"));
        assertFalse(new String(encrypted).contains("invite-code"));
        assertEquals(state, store.load().orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoExchangeHasBeenSaved() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void encryptsAndReloadsInviteAndPendingPairRequests() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.REGISTRATION_OPEN,
                "invite-code",
                List.of(
                        new ExchangeState.Participant(101L, "Alex", 102L),
                        new ExchangeState.Participant(102L, "Sam", 101L),
                        new ExchangeState.Participant(103L, "Jo", null),
                        new ExchangeState.Participant(104L, "Lee", null)),
                List.of(new ExchangeState.PairRequest(103L, 104L)),
                0,
                List.of(),
                Set.of(),
                Set.of());

        store.save(state);

        assertEquals(state, store.load().orElseThrow());
    }

    @Test
    void persistsClosedRegistrationAndItsRoster() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.REGISTRATION_CLOSED,
                null,
                List.of(new ExchangeState.Participant(101L, "Organizer", null)),
                List.of(),
                0,
                List.of(),
                Set.of(),
                Set.of());

        store.save(state);

        assertEquals(state, store.load().orElseThrow());
    }

    @Test
    void usesFreshEncryptionNonceForEverySave() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState state = sampleState();
        store.save(state);
        byte[] firstEncryption = Files.readAllBytes(store.stateFile());

        store.save(state);

        assertFalse(java.util.Arrays.equals(firstEncryption, Files.readAllBytes(store.stateFile())));
    }

    @Test
    void rejectsStateEncryptedWithAnotherKey() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        store.save(sampleState());
        ExchangeStore wrongKeyStore = new ExchangeStore(dataDirectory, differentKey());

        assertThrows(IOException.class, wrongKeyStore::load);
    }

    @Test
    void rejectsTamperedState() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        store.save(sampleState());
        byte[] encrypted = Files.readAllBytes(store.stateFile());
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(store.stateFile(), encrypted);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void rejectsTruncatedStateFile() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        Files.createDirectories(dataDirectory);
        Files.write(store.stateFile(), new byte[20]);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void rejectsUnknownStateFormat() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        Files.createDirectories(dataDirectory);
        Files.write(store.stateFile(), new byte[32]);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void rejectsOversizedStateFile() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        Files.createDirectories(dataDirectory);
        try (FileChannel channel = FileChannel.open(
                store.stateFile(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(16L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        assertThrows(IOException.class, store::load);
    }

    @Test
    void deletesSavedState() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        store.save(sampleState());

        store.delete();

        assertTrue(store.load().isEmpty());
    }

    @Test
    void preservesPreviousStateWhenAtomicReplacementFails() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState original = sampleState();
        store.save(original);
        ExchangeStore failingStore = new ExchangeStore(
                dataDirectory,
                KEY,
                (source, target) -> {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "Atomic move unavailable");
                });
        ExchangeState changed = new ExchangeState(
                ExchangeState.Phase.AWAITING_CONFIRMATIONS,
                null,
                original.participants(),
                List.of(),
                1,
                original.assignments(),
                Set.of(101L, 102L, 103L, 104L),
                Set.of(101L));

        assertThrows(IOException.class, () -> failingStore.save(changed));
        assertEquals(original, store.load().orElseThrow());
    }

    @Test
    void rejectsKeysThatAreNot256Bits() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExchangeStore(dataDirectory, new byte[31]));
    }

    private static ExchangeState sampleState() {
        return new ExchangeState(
                ExchangeState.Phase.DELIVERING,
                null,
                List.of(
                        new ExchangeState.Participant(101L, "Alex", 102L),
                        new ExchangeState.Participant(102L, "Sam", 101L),
                        new ExchangeState.Participant(103L, "Jo", null),
                        new ExchangeState.Participant(104L, "Lee", null)),
                List.of(),
                1,
                List.of(
                        new ExchangeState.Assignment(101L, 103L),
                        new ExchangeState.Assignment(102L, 104L),
                        new ExchangeState.Assignment(103L, 102L),
                        new ExchangeState.Assignment(104L, 101L)),
                Set.of(101L),
                Set.of());
    }

    private static byte[] differentKey() {
        byte[] key = new byte[32];
        key[0] = 1;
        return key;
    }
}
