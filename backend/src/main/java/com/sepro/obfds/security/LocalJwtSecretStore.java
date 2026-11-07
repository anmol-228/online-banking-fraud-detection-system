package com.sepro.obfds.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps one random signing secret per local installation, outside the repository.
 *
 * <p>A local demonstration has to keep its sessions across a backend restart, yet must not sign
 * tokens with a value that is public in the source code. The first start generates 48 random
 * bytes and stores them in the configured file; every later start reads the same value back.
 * Deleting the file rotates the secret on purpose and invalidates every issued token.</p>
 *
 * <p>The file lives next to the installation's other credentials and is never committed; on
 * systems with POSIX permissions it is created readable by the owner only. On Windows it inherits
 * the permissions of its folder, so keep that folder owner-restricted.</p>
 */
public final class LocalJwtSecretStore {

    private static final int SECRET_BYTES = 48;

    private LocalJwtSecretStore() {
    }

    /** Returns the stored secret, creating the file with a fresh random value when it is missing. */
    public static String loadOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (existing.length() >= 32) {
                    return existing;
                }
                throw new IllegalStateException(
                        "The JWT secret file " + file + " exists but holds fewer than 32 characters. "
                                + "Delete it to generate a new one (this signs every user out).");
            }
            byte[] random = new byte[SECRET_BYTES];
            new SecureRandom().nextBytes(random);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "jwt-secret", ".tmp");
            try {
                try {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // Not a POSIX file system (Windows): the folder's permissions apply.
                }
                Files.writeString(temp, secret + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, file);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            return secret;
        } catch (IOException ex) {
            throw new IllegalStateException("The JWT secret file " + file + " could not be read or created.", ex);
        }
    }
}
