package com.secretsanta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class PendingDrawStore {
    private static final int MAGIC = 0x53534452;
    private static final int VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MAX_ASSIGNMENTS = 10_000;
    private static final long MAX_FILE_SIZE = 16L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock DRAW_JVM_LOCK = new ReentrantLock();

    record Assignment(long giverId, String giverName, long receiverId, String receiverName) {}

    @FunctionalInterface
    interface AtomicMoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    record PendingDraw(List<Assignment> assignments, Set<Long> deliveredGiverIds) {
        PendingDraw {
            assignments = List.copyOf(assignments);
            deliveredGiverIds = Set.copyOf(deliveredGiverIds);
            if (assignments.isEmpty() || assignments.size() > MAX_ASSIGNMENTS) {
                throw new IllegalArgumentException("Pending draw size is invalid.");
            }

            Set<Long> giverIds = new HashSet<>();
            Set<Long> receiverIds = new HashSet<>();
            for (Assignment assignment : assignments) {
                if (assignment == null
                        || assignment.giverId() <= 0
                        || assignment.receiverId() <= 0
                        || assignment.giverId() == assignment.receiverId()
                        || assignment.giverName() == null
                        || assignment.giverName().isBlank()
                        || assignment.receiverName() == null
                        || assignment.receiverName().isBlank()
                        || !giverIds.add(assignment.giverId())
                        || !receiverIds.add(assignment.receiverId())) {
                    throw new IllegalArgumentException("Pending draw assignments are invalid.");
                }
            }
            if (!giverIds.equals(receiverIds) || !giverIds.containsAll(deliveredGiverIds)) {
                throw new IllegalArgumentException("Pending draw delivery progress is invalid.");
            }
        }

        PendingDraw markDelivered(long giverId) {
            Set<Long> delivered = new HashSet<>(deliveredGiverIds);
            delivered.add(giverId);
            return new PendingDraw(assignments, delivered);
        }
    }

    private final Path dataDirectory;
    private final Path pendingFile;
    private final Path drawLockFile;
    private final Path instanceLockFile;
    private final byte[] key;
    private final AtomicMoveOperation atomicMoveOperation;

    PendingDrawStore(Path dataDirectory, byte[] key) {
        this(
                dataDirectory,
                key,
                (source, target) -> Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING));
    }

    PendingDrawStore(
            Path dataDirectory, byte[] key, AtomicMoveOperation atomicMoveOperation) {
        if (dataDirectory == null || key == null || key.length != 32) {
            throw new IllegalArgumentException("A data directory and 256-bit key are required.");
        }
        this.dataDirectory = dataDirectory;
        this.pendingFile = dataDirectory.resolve("pending-draw.enc");
        this.drawLockFile = dataDirectory.resolve("pending-draw.lock");
        this.instanceLockFile = dataDirectory.resolve("bot-instance.lock");
        this.key = key.clone();
        this.atomicMoveOperation = Objects.requireNonNull(atomicMoveOperation);
    }

    ExchangeStore exchangeStore() {
        return new ExchangeStore(dataDirectory, key);
    }

    Optional<PendingDraw> load() throws IOException {
        if (!Files.exists(pendingFile)) {
            return Optional.empty();
        }
        if (Files.size(pendingFile) > MAX_FILE_SIZE) {
            throw new IOException("Persisted pending draw is too large.");
        }
        byte[] fileBytes = Files.readAllBytes(pendingFile);
        if (fileBytes.length < Integer.BYTES + NONCE_LENGTH + TAG_LENGTH_BITS / 8) {
            throw new IOException("Persisted pending draw is invalid.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(fileBytes);
        if (buffer.getInt() != MAGIC) {
            throw new IOException("Persisted pending draw has an unknown format.");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return Optional.of(decode(cipher.doFinal(ciphertext)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IOException("Could not decrypt or decode the pending draw.", exception);
        }
    }

    void save(PendingDraw pendingDraw) throws IOException {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            ciphertext = cipher.doFinal(encode(pendingDraw));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not encrypt the pending draw.", exception);
        }

        ByteBuffer fileBytes = ByteBuffer.allocate(Integer.BYTES + nonce.length + ciphertext.length);
        fileBytes.putInt(MAGIC).put(nonce).put(ciphertext).flip();
        Files.createDirectories(dataDirectory);
        Path temporaryFile = Files.createTempFile(dataDirectory, "pending-draw-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                while (fileBytes.hasRemaining()) {
                    channel.write(fileBytes);
                }
                channel.force(true);
            }
            atomicMoveOperation.move(temporaryFile, pendingFile);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    void delete() throws IOException {
        Files.deleteIfExists(pendingFile);
    }

    Path pendingFile() {
        return pendingFile;
    }

    LockHandle acquireDrawLock() throws IOException {
        Files.createDirectories(dataDirectory);
        DRAW_JVM_LOCK.lock();
        try {
            FileChannel channel = FileChannel.open(
                    drawLockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                return new LockHandle(channel, channel.lock(), DRAW_JVM_LOCK);
            } catch (IOException | RuntimeException exception) {
                channel.close();
                throw exception;
            }
        } catch (IOException | RuntimeException exception) {
            DRAW_JVM_LOCK.unlock();
            throw exception;
        }
    }

    InstanceLock acquireInstanceLock() throws IOException {
        Files.createDirectories(dataDirectory);
        FileChannel channel = FileChannel.open(
                instanceLockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            return new InstanceLock(channel, channel.lock());
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IOException("Another bot process is already using this data directory.", exception);
        } catch (IOException exception) {
            channel.close();
            throw exception;
        }
    }

    private static byte[] encode(PendingDraw pendingDraw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
            output.writeInt(pendingDraw.assignments().size());
            for (Assignment assignment : pendingDraw.assignments()) {
                output.writeLong(assignment.giverId());
                output.writeUTF(assignment.giverName());
                output.writeLong(assignment.receiverId());
                output.writeUTF(assignment.receiverName());
            }
            output.writeInt(pendingDraw.deliveredGiverIds().size());
            for (long giverId : pendingDraw.deliveredGiverIds()) {
                output.writeLong(giverId);
            }
        }
        return bytes.toByteArray();
    }

    private static PendingDraw decode(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != VERSION) {
                throw new IOException("Persisted pending draw version is unsupported.");
            }
            int assignmentCount = input.readInt();
            if (assignmentCount < 1 || assignmentCount > MAX_ASSIGNMENTS) {
                throw new IOException("Persisted pending draw size is invalid.");
            }
            List<Assignment> assignments = new ArrayList<>(assignmentCount);
            for (int index = 0; index < assignmentCount; index++) {
                assignments.add(new Assignment(
                        input.readLong(),
                        input.readUTF(),
                        input.readLong(),
                        input.readUTF()));
            }
            int deliveredCount = input.readInt();
            if (deliveredCount < 0 || deliveredCount > assignmentCount) {
                throw new IOException("Persisted pending draw progress is invalid.");
            }
            Set<Long> deliveredGiverIds = new HashSet<>();
            for (int index = 0; index < deliveredCount; index++) {
                if (!deliveredGiverIds.add(input.readLong())) {
                    throw new IOException("Persisted pending draw progress is invalid.");
                }
            }
            if (input.available() != 0) {
                throw new IOException("Persisted pending draw contains unexpected data.");
            }
            return new PendingDraw(assignments, deliveredGiverIds);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Persisted pending draw data is invalid.", exception);
        }
    }

    static final class LockHandle implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final ReentrantLock jvmLock;

        private LockHandle(FileChannel channel, FileLock lock, ReentrantLock jvmLock) {
            this.channel = channel;
            this.lock = lock;
            this.jvmLock = jvmLock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
                channel.close();
            } finally {
                jvmLock.unlock();
            }
        }
    }

    static final class InstanceLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;

        private InstanceLock(FileChannel channel, FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
