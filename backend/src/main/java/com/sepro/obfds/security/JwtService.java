package com.sepro.obfds.security;

import com.sepro.obfds.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and validates the stateless JSON Web Tokens used for authentication (FR-03, NFR-01).
 *
 * <p>The backend keeps no server-side session, which is what allows more than one instance of the
 * application to be run behind a load balancer (NFR-03, NFR-05).</p>
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLES = "roles";

    private final SecretKey signingKey;
    private final Duration expiry;

    public JwtService(AppProperties properties) {
        byte[] secretBytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "obfds.jwt.secret must be at least 32 characters long for HS256 signing");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expiry = Duration.ofMinutes(properties.getJwt().getExpiryMinutes());
    }

    /** Creates a signed token carrying the username and the granted authorities. */
    public String issueToken(String username, List<String> authorities) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_ROLES, authorities)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiry)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies the signature and expiry of a token.
     *
     * @return the token claims, or empty when the token is missing, malformed, expired or signed
     *     with a different key
     */
    public Optional<Claims> parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            // An invalid token is an expected condition, not a server fault. The request simply
            // stays unauthenticated and the entry point returns 401.
            return Optional.empty();
        }
    }

    public long getExpirySeconds() {
        return expiry.toSeconds();
    }
}
