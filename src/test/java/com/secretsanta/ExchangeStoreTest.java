package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
    void encryptsAndReloadsLateRegistrationWithThePreviousDraw() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.LATE_REGISTRATION_OPEN,
                "existing-invite",
                List.of(
                        new ExchangeState.Participant(101L, "Organizer", null),
                        new ExchangeState.Participant(102L, "Alex", null),
                        new ExchangeState.Participant(103L, "Sam", null),
                        new ExchangeState.Participant(104L, "Late friend", null)),
                List.of(),
                1,
                List.of(
                        new ExchangeState.Assignment(101L, 102L),
                        new ExchangeState.Assignment(102L, 103L),
                        new ExchangeState.Assignment(103L, 101L)),
                Set.of(101L, 102L, 103L),
                Set.of(101L));

        store.save(state);

        assertEquals(state, store.load().orElseThrow());
    }

    @Test
    void encryptsAndReloadsFirstAssignmentRecipients() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState original = sampleState();
        ExchangeState state = new ExchangeState(
                ExchangeState.Phase.DELIVERING,
                null,
                original.participants(),
                List.of(),
                2,
                original.assignments(),
                original.deliveredUserIds(),
                Set.of(),
                Set.of(104L));

        store.save(state);

        assertEquals(state, store.load().orElseThrow());
    }

    @Test
    void loadsVersionOneStateWithoutFirstAssignmentMetadata() throws Exception {
        ExchangeStore store = new ExchangeStore(dataDirectory, KEY);
        ExchangeState legacyState = sampleState();
        writeVersionOneState(store, legacyState);

        assertEquals(legacyState, store.load().orElseThrow());
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

    private static void writeVersionOneState(ExchangeStore store, ExchangeState state)
            throws Exception {
        ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(plaintext)) {
            output.writeInt(1);
            output.writeByte(state.phase().ordinal());
            output.writeBoolean(state.inviteCode() != null);
            if (state.inviteCode() != null) {
                output.writeUTF(state.inviteCode());
            }

            output.writeInt(state.participants().size());
            for (ExchangeState.Participant participant : state.participants()) {
                output.writeLong(participant.userId());
                output.writeUTF(participant.name());
                output.writeBoolean(participant.partnerId() != null);
                if (participant.partnerId() != null) {
                    output.writeLong(participant.partnerId());
                }
            }

            output.writeInt(state.pairRequests().size());
            for (ExchangeState.PairRequest request : state.pairRequests()) {
                output.writeLong(request.requesterId());
                output.writeLong(request.requestedPartnerId());
            }

            output.writeLong(state.drawGeneration());
            output.writeInt(state.assignments().size());
            for (ExchangeState.Assignment assignment : state.assignments()) {
                output.writeLong(assignment.giverId());
                output.writeLong(assignment.receiverId());
            }
            writeIds(output, state.deliveredUserIds());
            writeIds(output, state.confirmedUserIds());
        }

        byte[] nonce = new byte[12];
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(KEY, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext.toByteArray());
        ByteBuffer fileContents = ByteBuffer.allocate(Integer.BYTES + nonce.length + ciphertext.length);
        fileContents.putInt(0x53534558).put(nonce).put(ciphertext);
        Files.write(store.stateFile(), fileContents.array());
    }

    private static void writeIds(DataOutputStream output, Set<Long> ids) throws IOException {
        TreeSet<Long> sortedIds = new TreeSet<>(ids);
        output.writeInt(sortedIds.size());
        for (long id : sortedIds) {
            output.writeLong(id);
        }
    }

    private static byte[] differentKey() {
        byte[] key = new byte[32];
        key[0] = 1;
        return key;
    }
}
