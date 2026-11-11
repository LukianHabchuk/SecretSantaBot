package com.secretsanta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingDrawStoreTest {
    private static final byte[] KEY = new byte[32];

    @TempDir
    Path dataDirectory;

    @Test
    void encryptsAndReloadsPendingDrawWithDeliveryProgress() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        PendingDrawStore.PendingDraw pendingDraw = new PendingDrawStore.PendingDraw(
                List.of(
                        new PendingDrawStore.Assignment(201L, "Alex", 202L, "Sam"),
                        new PendingDrawStore.Assignment(202L, "Sam", 201L, "Alex")),
                Set.of(201L));

        store.save(pendingDraw);
        byte[] encrypted = Files.readAllBytes(store.pendingFile());

        assertFalse(new String(encrypted).contains("Alex"));
        assertFalse(new String(encrypted).contains("Sam"));
        assertEquals(pendingDraw, store.load().orElseThrow());
    }

    @Test
    void deletesCompletedPendingDraw() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        store.save(samplePendingDraw());

        store.delete();

        assertTrue(store.load().isEmpty());
    }

    @Test
    void usesFreshEncryptionNonceWhenUpdatingProgress() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        PendingDrawStore.PendingDraw pendingDraw = samplePendingDraw();
        store.save(pendingDraw);
        byte[] firstEncryption = Files.readAllBytes(store.pendingFile());

        store.save(pendingDraw);

        assertFalse(java.util.Arrays.equals(
                firstEncryption, Files.readAllBytes(store.pendingFile())));
    }

    @Test
    void preservesExistingPendingDrawWhenAtomicReplacementIsUnavailable() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        PendingDrawStore.PendingDraw original = samplePendingDraw();
        store.save(original);
        PendingDrawStore nonAtomicStore = new PendingDrawStore(
                dataDirectory,
                KEY,
                (source, target) -> {
                    throw new AtomicMoveNotSupportedException(
                            source.toString(), target.toString(), "Atomic move unavailable");
                });

        assertThrows(
                AtomicMoveNotSupportedException.class,
                () -> nonAtomicStore.save(original.markDelivered(201L)));
        assertEquals(original, store.load().orElseThrow());
    }

    @Test
    void rejectsPendingDrawEncryptedWithAnotherKey() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        store.save(samplePendingDraw());
        PendingDrawStore wrongKeyStore =
                new PendingDrawStore(dataDirectory, differentKey());

        assertThrows(IOException.class, wrongKeyStore::load);
    }

    @Test
    void rejectsTamperedPendingDraw() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        store.save(samplePendingDraw());
        byte[] encrypted = Files.readAllBytes(store.pendingFile());
        encrypted[encrypted.length - 1] ^= 1;
        Files.write(store.pendingFile(), encrypted);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void preventsMultipleBotProcessesFromUsingTheSameDataDirectory() throws Exception {
        PendingDrawStore store = new PendingDrawStore(dataDirectory, KEY);
        try (PendingDrawStore.InstanceLock ignored = store.acquireInstanceLock()) {
            assertThrows(IOException.class, store::acquireInstanceLock);
        }
    }

    @Test
    void rejectsDuplicateGiversAndUnknownDeliveredIds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingDrawStore.PendingDraw(
                        List.of(
                                new PendingDrawStore.Assignment(201L, "Alex", 202L, "Sam"),
                                new PendingDrawStore.Assignment(201L, "Sam", 203L, "Jo")),
                        Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PendingDrawStore.PendingDraw(
                        List.of(
                                new PendingDrawStore.Assignment(201L, "Alex", 202L, "Sam"),
                                new PendingDrawStore.Assignment(202L, "Sam", 201L, "Alex")),
                        Set.of(999L)));
    }

    private static PendingDrawStore.PendingDraw samplePendingDraw() {
        return new PendingDrawStore.PendingDraw(
                List.of(
                        new PendingDrawStore.Assignment(201L, "Alex", 202L, "Sam"),
                        new PendingDrawStore.Assignment(202L, "Sam", 201L, "Alex")),
                Set.of());
    }

    private static byte[] differentKey() {
        byte[] key = new byte[32];
        key[0] = 1;
        return key;
    }
}
