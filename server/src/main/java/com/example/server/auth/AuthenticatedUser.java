package com.example.server.auth;

public record AuthenticatedUser(
        Long userId,
        String username,
        String role
) {
}
