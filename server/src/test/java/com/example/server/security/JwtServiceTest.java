package com.example.server.security;

import com.example.server.service.AuthenticatedUser;
import com.example.server.entity.User;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    @Test
    void generatesAndParsesAuthenticatedUser() {
        JwtService jwtService = new JwtService(SECRET, 7200);
        User user = user();

        String token = jwtService.generateToken(user);
        AuthenticatedUser principal = jwtService.parseToken(token);

        assertThat(principal.userId()).isEqualTo(user.getId());
        assertThat(principal.username()).isEqualTo(user.getUsername());
        assertThat(principal.role()).isEqualTo(user.getRole());
    }

    @Test
    void rejectsTamperedAndExpiredTokens() {
        JwtService jwtService = new JwtService(SECRET, 7200);
        String token = jwtService.generateToken(user());

        assertThatThrownBy(() -> jwtService.parseToken(token + "tampered"))
                .isInstanceOf(JwtException.class);

        JwtService expiredJwtService = new JwtService(SECRET, -1);
        assertThatThrownBy(() -> expiredJwtService.parseToken(expiredJwtService.generateToken(user())))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsSecretsShorterThanThirtyTwoBytes() {
        assertThatThrownBy(() -> new JwtService("too-short", 7200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setUsername("rick");
        user.setRole("USER");
        return user;
    }
}
