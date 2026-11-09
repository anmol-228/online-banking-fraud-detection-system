package com.sepro.obfds;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps a promise made in the documentation: this system sends nothing to anyone.
 *
 * <p>The escalation feature prepares a mail DRAFT for a person to send. If someone later adds a
 * mail client, an HTTP client or a "delivered" state, that promise silently stops being true, so
 * this test fails and forces the change to be a deliberate, reviewed one (it needs the owner's
 * approval for an outbound provider). It scans the application source, not the tests.</p>
 */
class NoOutboundDeliveryTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final List<String> FORBIDDEN_CODE = List.of(
            "jakarta.mail", "javax.mail", "JavaMailSender", "MimeMessage", "spring-boot-starter-mail",
            "java.net.http", "RestTemplate", "WebClient", "RestClient", "HttpURLConnection",
            "HttpClient", "new Socket(", "ProcessBuilder", "Runtime.getRuntime().exec");

    /** Words that would claim a message reached someone. */
    private static final List<String> FORBIDDEN_STATES = List.of(
            "DELIVERED", "ESCALATION_SENT", "EMAIL_SENT", "REPORTED_TO_POLICE", "REPORTED_TO_BANK", "SENT_TO_AUTHORITY");

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> offenders(List<String> needles) throws IOException {
        List<String> found = new ArrayList<>();
        for (Path source : javaSources()) {
            String text = Files.readString(source);
            for (String needle : needles) {
                if (text.contains(needle)) {
                    found.add(MAIN.relativize(source) + " contains '" + needle + "'");
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("NOSEND-1 The application contains no mail, HTTP-client, socket or process-launching code")
    void noOutboundTransportExists() throws IOException {
        assertThat(javaSources()).as("sources were found, so this scan is not vacuous").hasSizeGreaterThan(50);
        assertThat(offenders(FORBIDDEN_CODE)).isEmpty();
    }

    @Test
    @DisplayName("NOSEND-2 No state or status claims that anything was delivered or reported")
    void noDeliveredState() throws IOException {
        assertThat(offenders(FORBIDDEN_STATES)).isEmpty();
    }

    @Test
    @DisplayName("NOSEND-3 The build declares no mail dependency")
    void noMailDependency() throws IOException {
        assertThat(Files.readString(Path.of("pom.xml"))).doesNotContain("starter-mail").doesNotContain("jakarta.mail");
    }
}
