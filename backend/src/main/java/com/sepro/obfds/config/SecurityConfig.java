package com.sepro.obfds.config;

import com.sepro.obfds.security.JwtAuthenticationFilter;
import com.sepro.obfds.security.RestAccessDeniedHandler;
import com.sepro.obfds.security.RestAuthenticationEntryPoint;
import com.sepro.obfds.security.SecurityHeadersFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Central authentication and authorization configuration (FR-03, FR-04, NFR-01).
 *
 * <p>Every rule below is deliberately explicit. The final {@code anyRequest().authenticated()}
 * means a new endpoint is protected by default rather than accidentally public.</p>
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AppProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityHeadersFilter securityHeadersFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final AppProperties appProperties;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityHeadersFilter securityHeadersFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            AppProperties appProperties) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.securityHeadersFilter = securityHeadersFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.appProperties = appProperties;
    }

    /** BCrypt is used so that no password is ever stored in a readable form (NFR-01). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints: registration, login and the health probe.
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // Customer banking functions.
                        .requestMatchers("/api/accounts/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/beneficiaries/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/transactions/**").hasRole("CUSTOMER")
                        .requestMatchers("/api/notifications/**").hasRole("CUSTOMER")

                        // Dispute handling: staff queue first, then the customer endpoints.
                        .requestMatchers("/api/disputes/queue").hasAnyRole("OPS_OFFICER", "BANK_ADMIN")
                        .requestMatchers("/api/disputes/*/resolve").hasAnyRole("OPS_OFFICER", "BANK_ADMIN")
                        .requestMatchers("/api/disputes/**").hasRole("CUSTOMER")

                        // Fraud monitoring and review.
                        .requestMatchers("/api/alerts/**").hasAnyRole("FRAUD_ANALYST", "BANK_ADMIN")
                        .requestMatchers("/api/fraud-cases/**").hasAnyRole("FRAUD_ANALYST", "BANK_ADMIN")

                        // Administration, auditing and reporting.
                        .requestMatchers("/api/admin/**").hasRole("BANK_ADMIN")
                        .requestMatchers("/api/audit/**").hasAnyRole("BANK_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers("/api/reports/**")
                        .hasAnyRole("BANK_ADMIN", "FRAUD_ANALYST", "OPS_OFFICER")

                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(securityHeadersFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Only the configured front-end origin may call the API from a browser. A wildcard origin is
     * deliberately not used (NFR-01).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(appProperties.getCors().getAllowedOrigins().split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
