package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sepro.obfds.config.AppProperties;
import com.sepro.obfds.security.JwtService;
import com.sepro.obfds.security.LocalJwtSecretStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A local installation must keep its sessions across a restart without ever signing with a value
 * that is public in the source code. A restart is simulated by building a second service from the
 * same properties.
 */
class LocalJwtSecretStoreTest {

    @TempDir Path dir;

    private JwtService serviceUsing(Path file) {
        AppProperties properties = new AppProperties();
        properties.getJwt().setSecret("");
        properties.getJwt().setSecretFile(file.toString());
        return new JwtService(properties);
    }

    @Test
    @DisplayName("First start creates a random secret file; a restart reads the same value back")
    void secretIsCreatedOnceAndReused() throws Exception {
        Path file = dir.resolve("nested/jwt.secret");

        String first = LocalJwtSecretStore.loadOrCreate(file);
        String second = LocalJwtSecretStore.loadOrCreate(file);

        assertThat(first).hasSizeGreaterThanOrEqualTo(32).isEqualTo(second);
        assertThat(Files.readString(file).trim()).isEqualTo(first);
    }

    @Test
    @DisplayName("Two installations get different secrets")
    void secretsDiffer() {
        assertThat(LocalJwtSecretStore.loadOrCreate(dir.resolve("a")))
                .isNotEqualTo(LocalJwtSecretStore.loadOrCreate(dir.resolve("b")));
    }

    @Test
    @DisplayName("A token issued before a backend restart is still valid after it")
    void tokensSurviveRestart() {
        Path file = dir.resolve("jwt.secret");
        String token = serviceUsing(file).issueToken("ravi.kumar", List.of("ROLE_CUSTOMER"));

        assertThat(serviceUsing(file).parseToken(token)).isPresent();
    }

    @Test
    @DisplayName("Deleting the secret file rotates it on purpose and invalidates every old token")
    void rotationInvalidatesOldTokens() throws Exception {
        Path file = dir.resolve("jwt.secret");
        String token = serviceUsing(file).issueToken("ravi.kumar", List.of("ROLE_CUSTOMER"));

        Files.delete(file);

        assertThat(serviceUsing(file).parseToken(token)).isEmpty();
    }

    @Test
    @DisplayName("A token signed with the published default secret is not accepted by a local install")
    void defaultSecretTokenIsRejectedByLocalInstall() {
        AppProperties published = new AppProperties();
        published.getJwt().setSecret("change-me-local-dev-secret-key-min-32-chars");
        String forged = new JwtService(published).issueToken("admin.bank", List.of("ROLE_BANK_ADMIN"));

        assertThat(serviceUsing(dir.resolve("jwt.secret")).parseToken(forged)).isEmpty();
    }

    @Test
    @DisplayName("A truncated secret file is refused rather than silently weakening the key")
    void shortFileIsRefused() throws Exception {
        Path file = dir.resolve("jwt.secret");
        Files.writeString(file, "too-short");

        assertThatThrownBy(() -> LocalJwtSecretStore.loadOrCreate(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fewer than 32");
    }
}
