package com.secretsanta;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.OptionalLong;

final class OrganizerStore {
    private static final Object CLAIM_LOCK = new Object();

    private final Path dataDirectory;
    private final Path organizerFile;
    private final Path lockFile;

    OrganizerStore(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.organizerFile = dataDirectory.resolve("organizer-id");
        this.lockFile = dataDirectory.resolve("organizer-id.lock");
    }

    OptionalLong organizerId() throws IOException {
        if (!Files.exists(organizerFile)) {
            return OptionalLong.empty();
        }

        String value = Files.readString(organizerFile, StandardCharsets.UTF_8).trim();
        try {
            long organizerId = Long.parseLong(value);
            if (organizerId > 0) {
                return OptionalLong.of(organizerId);
            }
        } catch (NumberFormatException exception) {
            throw new IOException("Persisted organizer ID is invalid.", exception);
        }
        throw new IOException("Persisted organizer ID is invalid.");
    }

    boolean claim(long telegramUserId) throws IOException {
        if (telegramUserId <= 0) {
            throw new IllegalArgumentException("Telegram user ID must be positive.");
        }

        Files.createDirectories(dataDirectory);
        synchronized (CLAIM_LOCK) {
            try (FileChannel channel = FileChannel.open(
                            lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (Files.exists(organizerFile)) {
                    return false;
                }

                Path temporaryFile = Files.createTempFile(dataDirectory, "organizer-", ".tmp");
                try {
                    Files.writeString(
                            temporaryFile,
                            Long.toString(telegramUserId),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING);
                    try {
                        Files.move(
                                temporaryFile,
                                organizerFile,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporaryFile, organizerFile);
                    }
                } finally {
                    Files.deleteIfExists(temporaryFile);
                }
                return true;
            }
        }
    }
}
