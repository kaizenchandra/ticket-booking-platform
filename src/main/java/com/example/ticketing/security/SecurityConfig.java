package com.example.ticketing.security;

import com.example.ticketing.shared.Store;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwords() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(Store db) {
        return name -> {
            var r = db.list("SELECT * FROM users WHERE id=? AND active", name);
            if (r.isEmpty()) throw new UsernameNotFoundException(name);
            var u = r.getFirst();
            return User.withUsername(name)
                    .password((String) u.get("password"))
                    .roles((String) u.get("role"))
                    .build();
        };
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity h, @Value("${ticketing.demo}") boolean demo)
            throws Exception {
        h.csrf(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        a ->
                                a.requestMatchers(
                                                "/",
                                                "/index.html",
                                                "/app.js",
                                                "/style.css",
                                                "/api/v1/register",
                                                "/api/v1/payments/webhooks/simulator",
                                                "/actuator/health/**")
                                        .permitAll()
                                        .requestMatchers("/api/v1/admin/**", "/actuator/**")
                                        .hasRole("ADMIN")
                                        .requestMatchers("/api/v1/organizer/**")
                                        .hasAnyRole("ORGANIZER", "ADMIN")
                                        .requestMatchers("/api/v1/tickets/*/validate")
                                        .hasAnyRole("ORGANIZER", "ADMIN")
                                        .anyRequest()
                                        .authenticated());
        if (demo) h.httpBasic(b -> {
        });
        else
            h.oauth2ResourceServer(
                    o ->
                            o.jwt(
                                    j ->
                                            j.jwtAuthenticationConverter(
                                                    jwt -> {
                                                        var roles = jwt.getClaimAsStringList("roles");
                                                        var auth =
                                                                roles == null
                                                                        ? java.util.List
                                                                        .<org.springframework.security.core.GrantedAuthority>of()
                                                                        : roles.stream()
                                                                        .map(
                                                                                r ->
                                                                                        (org.springframework.security.core.GrantedAuthority)
                                                                                                new org.springframework.security.core.authority
                                                                                                        .SimpleGrantedAuthority("ROLE_" + r))
                                                                        .toList();
                                                        return new org.springframework.security.oauth2.server.resource
                                                                .authentication.JwtAuthenticationToken(jwt, auth, jwt.getSubject());
                                                    })));
        return h.build();
    }

    @Bean
    ApplicationRunner demoUsers(
            Store db,
            PasswordEncoder encoder,
            @Value("${ticketing.demo}") boolean demo,
            @Value("${ticketing.secret}") String secret,
            @Value("${ticketing.webhook-secret}") String webhookSecret) {
        return args -> {
            if (!demo
                    && (secret.startsWith("local-only")
                    || webhookSecret.startsWith("local-webhook")
                    || webhookSecret.length() < 32))
                throw new IllegalStateException("Production secret must be configured");
            if (demo)
                for (String role : new String[]{"customer", "organizer", "admin"})
                    db.update(
                            "INSERT INTO users(id,password,role) VALUES(?,?,?) ON CONFLICT DO NOTHING",
                            role,
                            encoder.encode("demo-" + role),
                            role.toUpperCase());
        };
    }
}
