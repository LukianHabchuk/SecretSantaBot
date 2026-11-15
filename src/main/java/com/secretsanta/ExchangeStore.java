package com.secretsanta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class ExchangeStore {
    private static final int MAGIC = 0x53534558;
    private static final int VERSION = 2;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_FILE_SIZE = 16L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    interface AtomicMoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    private final Path dataDirectory;
    private final Path stateFile;
    private final byte[] key;
    private final AtomicMoveOperation atomicMoveOperation;

    ExchangeStore(Path dataDirectory, byte[] key) {
        this(
                dataDirectory,
                key,
                (source, target) -> Files.move(
                        source,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING));
    }

    ExchangeStore(
            Path dataDirectory, byte[] key, AtomicMoveOperation atomicMoveOperation) {
        if (dataDirectory == null || key == null || key.length != 32) {
            throw new IllegalArgumentException("A data directory and 256-bit key are required.");
        }
        this.dataDirectory = dataDirectory;
        this.stateFile = dataDirectory.resolve("exchange-state.enc");
        this.key = key.clone();
        this.atomicMoveOperation = Objects.requireNonNull(atomicMoveOperation);
    }

    synchronized Optional<ExchangeState> load() throws IOException {
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        long fileSize = Files.size(stateFile);
        if (fileSize < Integer.BYTES + NONCE_LENGTH + TAG_LENGTH_BITS / 8
                || fileSize > MAX_FILE_SIZE) {
            throw new IOException("Persisted exchange state size is invalid.");
        }

        byte[] fileContents = Files.readAllBytes(stateFile);
        if (fileContents.length < Integer.BYTES + NONCE_LENGTH + TAG_LENGTH_BITS / 8
                || fileContents.length > MAX_FILE_SIZE) {
            throw new IOException("Persisted exchange state size is invalid.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(fileContents);
        if (buffer.getInt() != MAGIC) {
            throw new IOException("Persisted exchange state has an unknown format.");
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
            throw new IOException("Could not decrypt or decode the exchange state.", exception);
        }
    }

    synchronized void save(ExchangeState state) throws IOException {
        Objects.requireNonNull(state, "Exchange state is required.");
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            ciphertext = cipher.doFinal(encode(state));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not encrypt the exchange state.", exception);
        }

        ByteBuffer fileBytes = ByteBuffer.allocate(Integer.BYTES + nonce.length + ciphertext.length);
        fileBytes.putInt(MAGIC).put(nonce).put(ciphertext).flip();
        Files.createDirectories(dataDirectory);
        Path temporaryFile = Files.createTempFile(dataDirectory, "exchange-state-", ".tmp");
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
            atomicMoveOperation.move(temporaryFile, stateFile);
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    synchronized void delete() throws IOException {
        Files.deleteIfExists(stateFile);
    }

    Path stateFile() {
        return stateFile;
    }

    private static byte[] encode(ExchangeState state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
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
            writeIds(output, state.firstAssignmentUserIds());
        }
        return bytes.toByteArray();
    }

    private static ExchangeState decode(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            int version = input.readInt();
            if (version < 1 || version > VERSION) {
                throw new IOException("Persisted exchange state version is unsupported.");
            }
            int phaseOrdinal = input.readUnsignedByte();
            ExchangeState.Phase[] phases = ExchangeState.Phase.values();
            if (phaseOrdinal >= phases.length) {
                throw new IOException("Persisted exchange phase is invalid.");
            }
            ExchangeState.Phase phase = phases[phaseOrdinal];
            String inviteCode = input.readBoolean() ? input.readUTF() : null;

            int participantCount = readCount(input);
            List<ExchangeState.Participant> participants = new ArrayList<>(participantCount);
            for (int index = 0; index < participantCount; index++) {
                long userId = input.readLong();
                String name = input.readUTF();
                Long partnerId = input.readBoolean() ? input.readLong() : null;
                participants.add(new ExchangeState.Participant(userId, name, partnerId));
            }

            int requestCount = readCount(input);
            List<ExchangeState.PairRequest> pairRequests = new ArrayList<>(requestCount);
            for (int index = 0; index < requestCount; index++) {
                pairRequests.add(new ExchangeState.PairRequest(
                        input.readLong(), input.readLong()));
            }

            long drawGeneration = input.readLong();
            int assignmentCount = readCount(input);
            List<ExchangeState.Assignment> assignments = new ArrayList<>(assignmentCount);
            for (int index = 0; index < assignmentCount; index++) {
                assignments.add(new ExchangeState.Assignment(
                        input.readLong(), input.readLong()));
            }
            Set<Long> deliveredUserIds = readIds(input);
            Set<Long> confirmedUserIds = readIds(input);
            Set<Long> firstAssignmentUserIds = version >= 2 ? readIds(input) : Set.of();
            if (input.available() != 0) {
                throw new IOException("Persisted exchange state contains unexpected data.");
            }

            return new ExchangeState(
                    phase,
                    inviteCode,
                    participants,
                    pairRequests,
                    drawGeneration,
                    assignments,
                    deliveredUserIds,
                    confirmedUserIds,
                    firstAssignmentUserIds);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Persisted exchange state data is invalid.", exception);
        }
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IOException("Persisted exchange state entry count is invalid.");
        }
        return count;
    }

    private static void writeIds(DataOutputStream output, Set<Long> ids) throws IOException {
        TreeSet<Long> sortedIds = new TreeSet<>(ids);
        output.writeInt(sortedIds.size());
        for (long id : sortedIds) {
            output.writeLong(id);
        }
    }

    private static Set<Long> readIds(DataInputStream input) throws IOException {
        int count = readCount(input);
        Set<Long> ids = new HashSet<>();
        for (int index = 0; index < count; index++) {
            if (!ids.add(input.readLong())) {
                throw new IOException("Persisted exchange state contains duplicate IDs.");
            }
        }
        return Set.copyOf(ids);
    }
}
