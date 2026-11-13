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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class LanguageStore {
    private static final int MAGIC = 0x53534c50;
    private static final int VERSION = 1;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_USERS = 100_000;
    private static final long MAX_FILE_SIZE = 4L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    @FunctionalInterface
    interface AtomicMoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    private final Path directory;
    private final Path file;
    private final byte[] key;
    private final AtomicMoveOperation move;
    private Map<Long, BotLanguage> preferences;

    LanguageStore(Path directory, byte[] key) throws IOException {
        this(directory, key, (source, target) -> Files.move(
                source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    LanguageStore(Path directory, byte[] key, AtomicMoveOperation move) throws IOException {
        if (directory == null || key == null || key.length != 32) {
            throw new IllegalArgumentException("A data directory and 256-bit key are required.");
        }
        this.directory = directory;
        this.file = directory.resolve("language-preferences.enc");
        this.key = key.clone();
        this.move = Objects.requireNonNull(move);
        this.preferences = load();
    }

    synchronized Optional<BotLanguage> get(long userId) {
        requireUserId(userId);
        return Optional.ofNullable(preferences.get(userId));
    }

    synchronized void set(long userId, BotLanguage language) throws IOException {
        requireUserId(userId);
        Objects.requireNonNull(language, "Language is required.");
        Map<Long, BotLanguage> next = new HashMap<>(preferences);
        next.put(userId, language);
        if (next.size() > MAX_USERS) {
            throw new IOException("Too many language preferences.");
        }
        save(next);
        preferences = next;
    }

    synchronized void delete(long userId) throws IOException {
        requireUserId(userId);
        if (!preferences.containsKey(userId)) {
            return;
        }
        Map<Long, BotLanguage> next = new HashMap<>(preferences);
        next.remove(userId);
        save(next);
        preferences = next;
    }

    private Map<Long, BotLanguage> load() throws IOException {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        long size = Files.size(file);
        if (size < Integer.BYTES + NONCE_LENGTH + TAG_BITS / 8 || size > MAX_FILE_SIZE) {
            throw new IOException("Language preferences file size is invalid.");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < Integer.BYTES + NONCE_LENGTH + TAG_BITS / 8
                || bytes.length > MAX_FILE_SIZE) {
            throw new IOException("Language preferences file size is invalid.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) {
            throw new IOException("Language preferences file format is invalid.");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return decode(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IOException("Could not decrypt language preferences.", exception);
        }
    }

    private void save(Map<Long, BotLanguage> next) throws IOException {
        byte[] nonce = new byte[NONCE_LENGTH];
        RANDOM.nextBytes(nonce);
        byte[] ciphertext;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            ciphertext = cipher.doFinal(encode(next));
        } catch (GeneralSecurityException exception) {
            throw new IOException("Could not encrypt language preferences.", exception);
        }
        ByteBuffer bytes = ByteBuffer.allocate(Integer.BYTES + nonce.length + ciphertext.length);
        bytes.putInt(MAGIC).put(nonce).put(ciphertext).flip();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "language-preferences-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            move.move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] encode(Map<Long, BotLanguage> preferences) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION);
            output.writeInt(preferences.size());
            for (Map.Entry<Long, BotLanguage> entry : new TreeMap<>(preferences).entrySet()) {
                output.writeLong(entry.getKey());
                output.writeUTF(entry.getValue().name());
            }
        }
        return bytes.toByteArray();
    }

    private static Map<Long, BotLanguage> decode(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            if (input.readInt() != VERSION) {
                throw new IOException("Language preferences version is unsupported.");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_USERS) {
                throw new IOException("Language preferences count is invalid.");
            }
            Map<Long, BotLanguage> result = new HashMap<>();
            for (int i = 0; i < count; i++) {
                long userId = input.readLong();
                requireUserId(userId);
                BotLanguage language = BotLanguage.valueOf(input.readUTF());
                if (result.putIfAbsent(userId, language) != null) {
                    throw new IOException("Language preferences contain duplicate users.");
                }
            }
            if (input.available() != 0) {
                throw new IOException("Language preferences contain unexpected data.");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new IOException("Language preferences are invalid.", exception);
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("User ID must be positive.");
        }
    }
}
