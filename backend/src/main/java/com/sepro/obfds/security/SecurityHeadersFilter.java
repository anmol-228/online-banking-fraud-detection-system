package com.sepro.obfds.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds baseline security-response headers to every request (NFR-01, documented known gap).
 *
 * <p>This is a standalone, additive filter — it does not touch authentication, authorization or
 * rate limiting, which stay separate, deferred roadmap items. The API returns only JSON and never
 * renders HTML, so the content security policy can be maximally strict without needing an
 * allow-list for scripts, styles or fonts.</p>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        // Only honoured by a browser over an HTTPS connection, so it is harmless to send over
        // plain HTTP in local development and becomes effective once the proposed reverse proxy
        // terminates TLS (see docs/deployment.md).
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        filterChain.doFilter(request, response);
    }
}
